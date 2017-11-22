// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.codeInspection.reference;

import com.intellij.ToolExtensionPoints;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.InspectionsBundle;
import com.intellij.codeInspection.lang.InspectionExtensionsFactory;
import com.intellij.codeInspection.lang.RefManagerExtension;
import com.intellij.lang.Language;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.diagnostic.Attachment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtilCore;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.NullableFactory;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.StringInterner;
import gnu.trove.THashMap;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class RefManagerImpl extends RefManager {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.reference.RefManager");

  private long myLastUsedMask = 0x0800_0000; // guarded by this

  @NotNull
  private final Project myProject;
  private AnalysisScope myScope;
  private RefProject myRefProject;

  private final BitSet myUnprocessedFiles = new BitSet();
  private final boolean processExternalElements = Registry.is("batch.inspections.process.external.elements");
  private final ConcurrentMap<PsiAnchor, RefElement> myRefTable = ContainerUtil.newConcurrentMap();

  private volatile List<RefElement> myCachedSortedRefs; // holds cached values from myPsiToRefTable/myRefTable sorted by containing virtual file; benign data race

  private final ConcurrentMap<Module, RefModule> myModules = ContainerUtil.newConcurrentMap();
  private final ProjectIterator myProjectIterator = new ProjectIterator();
  private final AtomicBoolean myDeclarationsFound = new AtomicBoolean(false);
  private final PsiManager myPsiManager;

  private volatile boolean myIsInProcess;
  private volatile boolean myOfflineView;

  private final LinkedHashSet<RefGraphAnnotator> myGraphAnnotators = new LinkedHashSet<>();
  private GlobalInspectionContext myContext;

  private final Map<Key, RefManagerExtension> myExtensions = new THashMap<>();
  private final Map<Language, RefManagerExtension> myLanguageExtensions = new HashMap<>();
  private final StringInterner myNameInterner = new StringInterner();

  public RefManagerImpl(@NotNull Project project, @Nullable AnalysisScope scope, @NotNull GlobalInspectionContext context) {
    myProject = project;
    myScope = scope;
    myContext = context;
    myPsiManager = PsiManager.getInstance(project);
    myRefProject = new RefProjectImpl(this);
    for (InspectionExtensionsFactory factory : Extensions.getExtensions(InspectionExtensionsFactory.EP_NAME)) {
      final RefManagerExtension extension = factory.createRefManagerExtension(this);
      if (extension != null) {
        myExtensions.put(extension.getID(), extension);
        myLanguageExtensions.put(extension.getLanguage(), extension);
      }
    }
    if (scope != null) {
      for (Module module : ModuleManager.getInstance(getProject()).getModules()) {
        getRefModule(module);
      }
    }
  }

  String internName(@NotNull String name) {
    synchronized (myNameInterner) {
      return myNameInterner.intern(name);
    }
  }

  @NotNull
  public GlobalInspectionContext getContext() {
    return myContext;
  }

  @Override
  public void iterate(@NotNull RefVisitor visitor) {
    for (RefElement refElement : getSortedElements()) {
      refElement.accept(visitor);
    }
    if (myModules != null) {
      for (RefModule refModule : myModules.values()) {
        if (myScope.containsModule(refModule.getModule())) refModule.accept(visitor);
      }
    }
    for (RefManagerExtension extension : myExtensions.values()) {
      extension.iterate(visitor);
    }
  }

  public void cleanup() {
    myScope = null;
    myRefProject = null;
    myRefTable.clear();
    myCachedSortedRefs = null;
    myModules.clear();
    myContext = null;

    myGraphAnnotators.clear();
    for (RefManagerExtension extension : myExtensions.values()) {
      extension.cleanup();
    }
    myExtensions.clear();
    myLanguageExtensions.clear();
  }

  @Nullable
  @Override
  public AnalysisScope getScope() {
    return myScope;
  }


  private void fireNodeInitialized(RefElement refElement) {
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onInitialize(refElement);
    }
  }

  void fireNodeMarkedReferenced(RefElement refWhat,
                                RefElement refFrom,
                                boolean referencedFromClassInitializer,
                                final boolean forReading,
                                final boolean forWriting) {
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onMarkReferenced(refWhat, refFrom, referencedFromClassInitializer, forReading, forWriting);
    }
  }

  public void fireNodeMarkedReferenced(PsiElement what, PsiElement from) {
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onMarkReferenced(what, from, false);
    }
  }

  void fireBuildReferences(RefElement refElement) {
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onReferencesBuild(refElement);
    }
  }

  public void registerGraphAnnotator(@NotNull RefGraphAnnotator annotator) {
    if (myGraphAnnotators.add(annotator) && annotator instanceof RefGraphAnnotatorEx) {
      ((RefGraphAnnotatorEx)annotator).initialize(this);
    }
  }

  @Override
  public synchronized long getLastUsedMask() {
    if (myLastUsedMask < 0) {
      throw new IllegalStateException("We're out of 64 bits, sorry");
    }
    myLastUsedMask *= 2;
    return myLastUsedMask;
  }

  @Override
  public <T> T getExtension(@NotNull final Key<T> key) {
    //noinspection unchecked
    return (T)myExtensions.get(key);
  }

  @Override
  @Nullable
  public String getType(@NotNull final RefEntity ref) {
    for (RefManagerExtension extension : myExtensions.values()) {
      final String type = extension.getType(ref);
      if (type != null) return type;
    }
    if (ref instanceof RefFile) {
      return SmartRefElementPointer.FILE;
    }
    if (ref instanceof RefModule) {
      return SmartRefElementPointer.MODULE;
    }
    if (ref instanceof RefProject) {
      return SmartRefElementPointer.PROJECT;
    }
    if (ref instanceof RefDirectory) {
      return SmartRefElementPointer.DIR;
    }
    return null;
  }

  @NotNull
  @Override
  public RefEntity getRefinedElement(@NotNull RefEntity ref) {
    for (RefManagerExtension extension : myExtensions.values()) {
      ref = extension.getRefinedElement(ref);
    }
    return ref;
  }

  @Override
  public Element export(@NotNull RefEntity refEntity, @NotNull final Element element, final int actualLine) {
    refEntity = getRefinedElement(refEntity);

    Element problem = new Element("problem");

    if (refEntity instanceof RefDirectory) {
      Element fileElement = new Element("file");
      VirtualFile virtualFile = ((PsiDirectory)((RefDirectory)refEntity).getElement()).getVirtualFile();
      fileElement.addContent(virtualFile.getUrl());
      problem.addContent(fileElement);
    }
    else if (refEntity instanceof RefElement) {
      final RefElement refElement = (RefElement)refEntity;
      final SmartPsiElementPointer pointer = refElement.getPointer();
      PsiFile psiFile = pointer.getContainingFile();
      if (psiFile == null) return null;

      Element fileElement = new Element("file");
      Element lineElement = new Element("line");
      final VirtualFile virtualFile = psiFile.getVirtualFile();
      LOG.assertTrue(virtualFile != null);
      fileElement.addContent(virtualFile.getUrl());

      if (actualLine == -1) {
        final Document document = PsiDocumentManager.getInstance(pointer.getProject()).getDocument(psiFile);
        LOG.assertTrue(document != null);
        final Segment range = pointer.getRange();
        lineElement.addContent(String.valueOf(range != null ? document.getLineNumber(range.getStartOffset()) + 1 : -1));
      }
      else {
        lineElement.addContent(String.valueOf(actualLine + 1));
      }

      problem.addContent(fileElement);
      problem.addContent(lineElement);

      appendModule(problem, refElement.getModule());
    }
    else if (refEntity instanceof RefModule) {
      final RefModule refModule = (RefModule)refEntity;
      final VirtualFile moduleFile = refModule.getModule().getModuleFile();
      final Element fileElement = new Element("file");
      fileElement.addContent(moduleFile != null ? moduleFile.getUrl() : refEntity.getName());
      problem.addContent(fileElement);
      appendModule(problem, refModule);
    }

    for (RefManagerExtension extension : myExtensions.values()) {
      extension.export(refEntity, problem);
    }

    new SmartRefElementPointerImpl(refEntity, true).writeExternal(problem);
    element.addContent(problem);
    return problem;
  }

  @Override
  @Nullable
  public String getGroupName(@NotNull final RefElement entity) {
    for (RefManagerExtension extension : myExtensions.values()) {
      final String groupName = extension.getGroupName(entity);
      if (groupName != null) return groupName;
    }

    RefEntity parent = entity.getOwner();
    while (parent != null && !(parent instanceof RefDirectory)) {
      parent = parent.getOwner();
    }
    final LinkedList<String> containingDirs = new LinkedList<>();
    while (parent instanceof RefDirectory) {
      containingDirs.addFirst(parent.getName());
      parent = parent.getOwner();
    }
    return containingDirs.isEmpty() ? null : StringUtil.join(containingDirs, File.separator);
  }

  private static void appendModule(final Element problem, final RefModule refModule) {
    if (refModule != null) {
      Element moduleElement = new Element("module");
      moduleElement.addContent(refModule.getName());
      problem.addContent(moduleElement);
    }
  }

  public void findAllDeclarations() {
    if (!myDeclarationsFound.getAndSet(true)) {
      long before = System.currentTimeMillis();
      final AnalysisScope scope = getScope();
      if (scope != null) {
        scope.accept(myProjectIterator);
      }

      LOG.info("Total duration of processing project usages:" + (System.currentTimeMillis() - before));
    }
  }

  public boolean isDeclarationsFound() {
    return myDeclarationsFound.get();
  }

  public void inspectionReadActionStarted() {
    myIsInProcess = true;
  }

  public void inspectionReadActionFinished() {
    myIsInProcess = false;
    if (myScope != null) myScope.invalidate();

    myCachedSortedRefs = null;
  }

  public void startOfflineView() {
    myOfflineView = true;
  }

  boolean isOfflineView() {
    return myOfflineView;
  }
  
  public boolean isInProcess() {
    return myIsInProcess;
  }

  @NotNull
  @Override
  public Project getProject() {
    return myProject;
  }

  @NotNull
  @Override
  public RefProject getRefProject() {
    return myRefProject;
  }

  @NotNull
  public List<RefElement> getSortedElements() {
    List<RefElement> answer = myCachedSortedRefs;
    if (answer != null) return answer;

    answer = new ArrayList<>(myRefTable.values());
    List<RefElement> list = answer;
    ReadAction.run(() -> ContainerUtil.quickSort(list, (o1, o2) -> {
      VirtualFile v1 = ((RefElementImpl)o1).getVirtualFile();
      VirtualFile v2 = ((RefElementImpl)o2).getVirtualFile();
      return (v1 != null ? v1.hashCode() : 0) - (v2 != null ? v2.hashCode() : 0);
    }));
    myCachedSortedRefs = answer = Collections.unmodifiableList(answer);
    return answer;
  }

  @NotNull
  @Override
  public PsiManager getPsiManager() {
    return myPsiManager;
  }

  @Override
  public synchronized boolean isInGraph(VirtualFile file) {
    return !myUnprocessedFiles.get(((VirtualFileWithId)file).getId());
  }

  @Nullable
  @Override
  public PsiNamedElement getContainerElement(@NotNull PsiElement element) {
    Language language = element.getLanguage();
    return myExtensions
      .values()
      .stream()
      .filter(extension -> extension.getLanguage().equals(language))
      .map(extension -> extension.getElementContainer(element))
      .filter(Objects::nonNull)
      .findFirst()
      .orElse(null);
  }

  private synchronized void registerUnprocessed(VirtualFileWithId virtualFile) {
    myUnprocessedFiles.set(virtualFile.getId());
  }

  void removeReference(@NotNull RefElement refElem) {
    final PsiElement element = refElem.getElement();
    final RefManagerExtension extension = element != null ? getExtension(element.getLanguage()) : null;
    if (extension != null) {
      extension.removeReference(refElem);
    }

    if (element != null &&
        myRefTable.remove(createAnchor(element)) != null) return;

    //PsiElement may have been invalidated and new one returned by getElement() is different so we need to do this stuff.
    for (Map.Entry<PsiAnchor, RefElement> entry : myRefTable.entrySet()) {
      RefElement value = entry.getValue();
      PsiAnchor anchor = entry.getKey();
      if (value == refElem) {
        myRefTable.remove(anchor);
        break;
      }
    }
    myCachedSortedRefs = null;
  }

  @NotNull
  private static PsiAnchor createAnchor(@NotNull final PsiElement element) {
    return ReadAction.compute(() -> PsiAnchor.create(element));
  }

  public void initializeAnnotators() {
    ExtensionPoint<RefGraphAnnotator> point = Extensions.getRootArea().getExtensionPoint(ToolExtensionPoints.INSPECTIONS_GRAPH_ANNOTATOR);
    final RefGraphAnnotator[] graphAnnotators = point.getExtensions();
    for (RefGraphAnnotator annotator : graphAnnotators) {
      registerGraphAnnotator(annotator);
    }
  }

  private class ProjectIterator extends PsiElementVisitor {
    @Override
    public void visitElement(PsiElement element) {
      ProgressManager.checkCanceled();
      final RefManagerExtension extension = getExtension(element.getLanguage());
      if (extension != null) {
        extension.visitElement(element);
      }
      else if (processExternalElements) {
        PsiFile file = element.getContainingFile();
        if (file != null) {
          boolean referencesProcessed = false;
          for (RefManagerExtension<?> managerExtension : myExtensions.values()) {
            if (managerExtension.shouldProcessExternalFile(file)) {
              RefElement refFile = getReference(file);
              LOG.assertTrue(refFile != null, file);
              if (!referencesProcessed) {
                referencesProcessed = true;
                for (PsiReference reference : element.getReferences()) {
                  PsiElement resolve = reference.resolve();
                  if (resolve != null) {
                    fireNodeMarkedReferenced(resolve, file);
                    RefElement refWhat = getReference(resolve);
                    if (refWhat == null) {
                      PsiFile targetContainingFile = resolve.getContainingFile();
                      //no logic to distinguish different elements in the file anyway
                      if (file == targetContainingFile) continue;
                      refWhat = getReference(targetContainingFile);
                    }

                    if (refWhat != null) {
                      ((RefElementImpl)refWhat).addInReference(refFile);
                      ((RefElementImpl)refFile).addOutReference(refWhat);
                    }
                  }
                }
              }

              Stream<? extends PsiElement> implicitRefs = managerExtension.extractExternalFileImplicitReferences(file);
              implicitRefs.forEach(e -> {
                RefElement superClassReference = getReference(e);
                if (superClassReference != null) {
                  //in case of implicit inheritance, e.g. GroovyObject
                  //= no explicit reference is provided, dependency on groovy library could be treated as redundant though it is not
                  //inReference is not important in this case
                  ((RefElementImpl)refFile).addOutReference(superClassReference);
                }
              });
            }
          }

          if (!referencesProcessed && element instanceof PsiFile) {
            VirtualFile virtualFile = PsiUtilCore.getVirtualFile(element);
            if (virtualFile instanceof VirtualFileWithId) {
              registerUnprocessed((VirtualFileWithId)virtualFile);
            }
          }
        }
      }
      for (PsiElement aChildren : element.getChildren()) {
        aChildren.accept(this);
      }
    }

    @Override
    public void visitFile(PsiFile file) {
      final VirtualFile virtualFile = file.getVirtualFile();
      if (virtualFile != null) {
        String relative = ProjectUtilCore.displayUrlRelativeToProject(virtualFile, virtualFile.getPresentableUrl(), myProject, true, false);
        myContext.incrementJobDoneAmount(myContext.getStdJobDescriptors().BUILD_GRAPH, relative);
      }
      final FileViewProvider viewProvider = file.getViewProvider();
      final Set<Language> relevantLanguages = viewProvider.getLanguages();
      for (Language language : relevantLanguages) {
        try {
          visitElement(viewProvider.getPsi(language));
        }
        catch (ProcessCanceledException | IndexNotReadyException e) {
          throw e;
        }
        catch (Throwable e) {
          LOG.error(new RuntimeExceptionWithAttachments(e, new Attachment("diagnostics.txt", file.getName())));
        }
      }
      myPsiManager.dropResolveCaches();
      InjectedLanguageManager.getInstance(myProject).dropFileCaches(file);
    }
  }

  @Override
  @Nullable
  public RefElement getReference(@Nullable PsiElement elem) {
    return getReference(elem, false);
  }

  @Nullable
  public RefElement getReference(PsiElement elem, final boolean ignoreScope) {
    if (ReadAction.compute(() -> elem == null || !elem.isValid() ||
                                 elem instanceof LightElement || !(elem instanceof PsiDirectory) && !belongsToScope(elem, ignoreScope))) {
      return null;
    }

    return getFromRefTableOrCache(
      elem,
      () -> ApplicationManager.getApplication().runReadAction(new Computable<RefElementImpl>() {
        @Override
        @Nullable
        public RefElementImpl compute() {
          final RefManagerExtension extension = getExtension(elem.getLanguage());
          if (extension != null) {
            final RefElement refElement = extension.createRefElement(elem);
            if (refElement != null) return (RefElementImpl)refElement;
          }
          if (elem instanceof PsiFile) {
            return new RefFileImpl((PsiFile)elem, RefManagerImpl.this);
          }
          if (elem instanceof PsiDirectory) {
            return new RefDirectoryImpl((PsiDirectory)elem, RefManagerImpl.this);
          }
          return null;
        }
      }),
      element -> ReadAction.run(() -> {
        element.initialize();
        for (RefManagerExtension each : myExtensions.values()) {
          each.onEntityInitialized(element, elem);
        }
        fireNodeInitialized(element);
      }));
  }

  private RefManagerExtension getExtension(final Language language) {
    return myLanguageExtensions.get(language);
  }

  @Nullable
  @Override
  public RefEntity getReference(final String type, final String fqName) {
    for (RefManagerExtension extension : myExtensions.values()) {
      final RefEntity refEntity = extension.getReference(type, fqName);
      if (refEntity != null) return refEntity;
    }
    if (SmartRefElementPointer.FILE.equals(type)) {
      return RefFileImpl.fileFromExternalName(this, fqName);
    }
    if (SmartRefElementPointer.MODULE.equals(type)) {
      return RefModuleImpl.moduleFromName(this, fqName);
    }
    if (SmartRefElementPointer.PROJECT.equals(type)) {
      return getRefProject();
    }
    if (SmartRefElementPointer.DIR.equals(type)) {
      String url = VfsUtilCore.pathToUrl(PathMacroManager.getInstance(getProject()).expandPath(fqName));
      VirtualFile vFile = VirtualFileManager.getInstance().findFileByUrl(url);
      if (vFile != null) {
        final PsiDirectory dir = PsiManager.getInstance(getProject()).findDirectory(vFile);
        return getReference(dir);
      }
    }
    return null;
  }

  @Nullable
  <T extends RefElement> T getFromRefTableOrCache(final PsiElement element, @NotNull NullableFactory<T> factory) {
    return getFromRefTableOrCache(element, factory, null); 
  }
  
  @Nullable
  private <T extends RefElement> T getFromRefTableOrCache(@NotNull PsiElement element,
                                                          @NotNull NullableFactory<T> factory,
                                                          @Nullable Consumer<T> whenCached) {

    PsiAnchor psiAnchor = createAnchor(element);
    //noinspection unchecked
    T result = (T)(myRefTable.get(psiAnchor));

    if (result != null) return result;

    if (!isValidPointForReference()) {
      //LOG.assertTrue(true, "References may become invalid after process is finished");
      return null;
    }

    result = factory.create();
    if (result == null) return null;

    myCachedSortedRefs = null;
    RefElement prev = myRefTable.putIfAbsent(psiAnchor, result);
    if (prev != null) {
      //noinspection unchecked
      result = (T)prev;
    }
    else if (whenCached != null) {
      whenCached.consume(result);
    }

    return result;
  }

  @Override
  public RefModule getRefModule(@Nullable Module module) {
    if (module == null) {
      return null;
    }
    RefModule refModule = myModules.get(module);
    if (refModule == null) {
      refModule = ConcurrencyUtil.cacheOrGet(myModules, module, new RefModuleImpl(module, this));
    }
    return refModule;
  }

  @Override
  public boolean belongsToScope(final PsiElement psiElement) {
    return belongsToScope(psiElement, false);
  }

  private boolean belongsToScope(final PsiElement psiElement, final boolean ignoreScope) {
    if (psiElement == null || !psiElement.isValid()) return false;
    if (psiElement instanceof PsiCompiledElement) return false;
    final PsiFile containingFile = ReadAction.compute(psiElement::getContainingFile);
    if (containingFile == null) {
      return false;
    }
    for (RefManagerExtension extension : myExtensions.values()) {
      if (!extension.belongsToScope(psiElement)) return false;
    }
    final Boolean inProject = ReadAction.compute(() -> psiElement.getManager().isInProject(psiElement));
    return inProject.booleanValue() && (ignoreScope || getScope() == null || getScope().contains(psiElement));
  }

  @Override
  public String getQualifiedName(RefEntity refEntity) {
    if (refEntity == null || refEntity instanceof RefElementImpl && !refEntity.isValid()) {
      return InspectionsBundle.message("inspection.reference.invalid");
    }

    return refEntity.getQualifiedName();
  }

  @Override
  public void removeRefElement(@NotNull RefElement refElement, @NotNull List<RefElement> deletedRefs) {
    List<RefEntity> children = refElement.getChildren();
    RefElement[] refElements = children.toArray(new RefElement[children.size()]);
    for (RefElement refChild : refElements) {
      removeRefElement(refChild, deletedRefs);
    }

    ((RefManagerImpl)refElement.getRefManager()).removeReference(refElement);
    ((RefElementImpl)refElement).referenceRemoved();
    if (!deletedRefs.contains(refElement)) {
      deletedRefs.add(refElement);
    }
    else {
      LOG.error("deleted second time");
    }
  }

  boolean isValidPointForReference() {
    return myIsInProcess || myOfflineView || ApplicationManager.getApplication().isUnitTestMode();
  }
}
