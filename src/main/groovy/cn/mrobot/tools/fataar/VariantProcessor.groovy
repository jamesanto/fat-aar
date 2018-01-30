package cn.mrobot.tools.fataar

import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.jvm.tasks.Jar

/**
 * 依赖库内容合并类
 * 合并内容: 1. class
 *           2. manifest
 *           3. resources
 *           4. RSources
 *           5. assets
 *           6. jniLibs
 *           7. proguardFile //TODO 2017-06-22 混淆文件合并待完成
 * Created by Vigi on 2017/2/24.
 * Modified by Devil on 2017/06/22
 */
class VariantProcessor {

    private final Project mProject

    private final LibraryVariant mVariant

    private Collection<AndroidArchiveLibrary> mAndroidArchiveLibraries = new ArrayList<>()

    private Collection<File> mJarFiles = new ArrayList<>()

    private Collection<ExcludeFile> mExcludeFiles = new ArrayList<>()

    public VariantProcessor(Project project, LibraryVariant variant) {
        mProject = project
        mVariant = variant
    }

    public void addAndroidArchiveLibrary(AndroidArchiveLibrary library) {
        mAndroidArchiveLibraries.add(library)
    }

    public void addJarFile(File jar) {
        mJarFiles.add(jar)
    }

    public void addExcludeFiles(List<ExcludeFile> excludeFiles) {
        mExcludeFiles.addAll(excludeFiles)
    }

    public void processVariant() {
        String taskPath = 'prepare' + mVariant.name.capitalize() + 'Dependencies'
        Task prepareTask = mProject.tasks.findByPath(taskPath)
        if (prepareTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }

        processClassesAndJars()

        if (mAndroidArchiveLibraries.isEmpty()) {
            return
        }
        processManifest()
        processResourcesAndR()
        processRSources()
        processAssets()
        processJniLibs()
        processProguardTxt(prepareTask)
        mergeRClass()
        processExcludeFiles()
    }

    /**
     * merge manifest
     *
     * TODO process each variant.getOutputs()
     * TODO "InvokeManifestMerger" deserve more android plugin version check
     * TODO add setMergeReportFile
     * TODO a better temp manifest file location
     */
    private void processManifest() {
        Class invokeManifestTaskClazz = null
        String className = 'com.android.build.gradle.tasks.InvokeManifestMerger'
        try {
            invokeManifestTaskClazz = Class.forName(className)
        } catch (ClassNotFoundException ignored) {
        }
        if (invokeManifestTaskClazz == null) {
            throw new RuntimeException("Can not find class ${className}!")
        }
        Task processManifestTask = mVariant.getOutputs().get(0).getProcessManifest()
        def manifestOutput = mProject.file(mProject.buildDir.path + '/intermediates/fat-aar/' + mVariant.dirName + '/AndroidManifest.xml')
        File manifestOutputBackup = processManifestTask.getManifestOutputFile()
        processManifestTask.setManifestOutputFile(manifestOutput)

        Task manifestsMergeTask = mProject.tasks.create('merge' + mVariant.name.capitalize() + 'Manifest', invokeManifestTaskClazz)
        manifestsMergeTask.setVariantName(mVariant.name)
        manifestsMergeTask.setMainManifestFile(manifestOutput)
        List<File> list = new ArrayList<>()
        for (archiveLibrary in mAndroidArchiveLibraries) {
            list.add(archiveLibrary.getManifest())
        }
        manifestsMergeTask.setSecondaryManifestFiles(list)
        manifestsMergeTask.setOutputFile(manifestOutputBackup)
        manifestsMergeTask.dependsOn processManifestTask
        processManifestTask.finalizedBy manifestsMergeTask
    }
    /**
     * 合并所有的类文件
     */
    private void processClassesAndJars() {
        if (mVariant.getBuildType().isMinifyEnabled()) {
            //merge proguard file
            for (archiveLibrary in mAndroidArchiveLibraries) {
                List<File> thirdProguardFiles = archiveLibrary.proguardRules
                for (File file : thirdProguardFiles) {
                    if (file.exists()) {
                        println 'add proguard file: ' + file.absolutePath
                        mProject.android.getDefaultConfig().proguardFile(file)
                    }
                }
            }
            //merge aar class
            Task javacTask = mVariant.getJavaCompile()
            if (javacTask == null) {
                // warn: can not find javaCompile task, jack compile might be on.
                return
            }
            javacTask.doLast {
                def dustDir = mProject.file(mProject.buildDir.path + '/intermediates/classes/' + mVariant.dirName)
                ExplodedHelper.processIntoClasses(mProject, mAndroidArchiveLibraries, mJarFiles, dustDir)
            }
        }

        String taskPath = 'transformClassesAndResourcesWithSyncLibJarsFor' + mVariant.name.capitalize()
        Task syncLibTask = mProject.tasks.findByPath(taskPath)
        if (syncLibTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        syncLibTask.doLast {
            def dustDir = mProject.file(AndroidPluginHelper.resolveBundleDir(mProject, mVariant).path + '/libs')
            ExplodedHelper.processIntoJars(mProject, mAndroidArchiveLibraries, mJarFiles, dustDir, mVariant.getBuildType().isMinifyEnabled())
        }

    }

    /**
     * merge R.txt(actually is to fix issue caused by provided configuration) and res
     *
     * Here I have to inject res into "main" instead of "variant.name".
     * To avoid the res from embed dependencies being used, once they have the same res Id with main res.
     *
     * Now the same res Id will cause a build exception: Duplicate resources, to encourage you to change res Id.
     * Adding "android.disableResourceValidation=true" to "gradle.properties" can do a trick to skip the exception, but is not recommended.
     */
    private void processResourcesAndR() {
        String taskPath = 'generate' + mVariant.name.capitalize() + 'Resources'
        Task resourceGenTask = mProject.tasks.findByPath(taskPath)
        if (resourceGenTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        resourceGenTask.doFirst {
            for (archiveLibrary in mAndroidArchiveLibraries) {
                mProject.android.sourceSets."main".res.srcDir(archiveLibrary.resFolder)
            }
        }
    }

    /**
     * generate R.java
     */
    private void processRSources() {
        Task processResourcesTask = mVariant.getOutputs().get(0).getProcessResources()
        processResourcesTask.doLast {
            for (archiveLibrary in mAndroidArchiveLibraries) {
                RSourceGenerator.generate(processResourcesTask.getSourceOutputDir(), archiveLibrary)
            }
        }
    }

    /**
     * merge assets
     *
     * AaptOptions.setIgnoreAssets and AaptOptions.setIgnoreAssetsPattern will work as normal
     */
    private void processAssets() {
        Task assetsTask = mVariant.getMergeAssets()
        if (assetsTask == null) {
            throw new RuntimeException("Can not find task in variant.getMergeAssets()!")
        }
        for (archiveLibrary in mAndroidArchiveLibraries) {
            assetsTask.getInputs().dir(archiveLibrary.assetsFolder)
        }
        assetsTask.doFirst {
            for (archiveLibrary in mAndroidArchiveLibraries) {
                // the source set here should be main or variant?
                mProject.android.sourceSets."main".assets.srcDir(archiveLibrary.assetsFolder)
            }
        }
    }

    /**
     * merge jniLibs
     */
    private void processJniLibs() {
        String taskPath = 'merge' + mVariant.name.capitalize() + 'JniLibFolders'
        Task mergeJniLibsTask = mProject.tasks.findByPath(taskPath)
        if (mergeJniLibsTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        for (archiveLibrary in mAndroidArchiveLibraries) {
            mergeJniLibsTask.getInputs().dir(archiveLibrary.jniFolder)
        }
        mergeJniLibsTask.doFirst {
            for (archiveLibrary in mAndroidArchiveLibraries) {
                // the source set here should be main or variant?
                mProject.android.sourceSets."main".jniLibs.srcDir(archiveLibrary.jniFolder)
            }
        }
    }

    /**
     * merge proguard.txt
     */
    private void processProguardTxt(Task prepareTask) {
        String taskPath = 'merge' + mVariant.name.capitalize() + 'ProguardFiles'
        Task mergeFileTask = mProject.tasks.findByPath(taskPath)
        if (mergeFileTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        for (archiveLibrary in mAndroidArchiveLibraries) {
            List<File> thirdProguardFiles = archiveLibrary.proguardRules
            for (File file : thirdProguardFiles) {
                if (file.exists()) {
                    println 'add proguard file: ' + file.absolutePath
                    mergeFileTask.getInputs().file(file)
                }
            }
        }
        mergeFileTask.doFirst {
            Collection proguardFiles = mergeFileTask.getInputFiles()
            for (archiveLibrary in mAndroidArchiveLibraries) {
                List<File> thirdProguardFiles = archiveLibrary.proguardRules
                for (File file : thirdProguardFiles) {
                    if (file.exists()) {
                        println 'add proguard file: ' + file.absolutePath
                        proguardFiles.add(file)
                    }
                }
            }
        }
        mergeFileTask.dependsOn prepareTask
    }
    /**
     * merge android library R.class
     */
    private void mergeRClass() {
        String taskPath = 'bundle' + mVariant.name.capitalize()
        Task bundleTask = mProject.tasks.findByPath(taskPath)
        if (bundleTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        taskPath = 'transformClassesAndResourcesWithSyncLibJarsFor' + mVariant.name.capitalize()
        Task syncLibTask = mProject.tasks.findByPath(taskPath)
        if (syncLibTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }

        // 原始jar包文件
        def classesJar = mProject.file(AndroidPluginHelper.resolveBundleDir(mProject, mVariant).path + '/classes.jar')
        println mVariant.getApplicationId()
        String applicationId = mVariant.getApplicationId();
        String excludeRPath = applicationId.replace('.', '/');
        println excludeRPath
        Task jarTask
        //开启混淆时对混淆过后的文件进行重新打包
        if (mVariant.getBuildType().isMinifyEnabled()) {
            jarTask = mProject.tasks.create(name: 'transformProguradJarTask' + mVariant.name, type: Jar) {
                from project.zipTree(AndroidPluginHelper.resolveTransform(mProject, mVariant))
                exclude(excludeRPath + '/R.class', excludeRPath + '/R$*', 'META-INF/')
            }
            jarTask.onlyIf {
                File file = AndroidPluginHelper.resolveTransform(mProject, mVariant);
                return file.exists();
            }
            jarTask.doLast {
                println 'transform progurad jar ready'
                File file = new File(mProject.getBuildDir().absolutePath + '/libs/' + mProject.name + '.jar');
                if (file.exists()) {
                    mProject.delete(classesJar)
                    mProject.copy {
                        from(file)
                        into(AndroidPluginHelper.resolveBundleDir(mProject, mVariant))
                        rename(mProject.name + '.jar', 'classes.jar')
                    }
                } else {
                    println 'can not find transformProguradJar file ';
                }
            }
        } else {
            jarTask = mProject.tasks.create(name: 'transformJarTask' + mVariant.name, type: Jar) {
                from(mProject.buildDir.absolutePath + '/intermediates/classes/' + mVariant.name.capitalize())
                exclude(excludeRPath + '/R.class', excludeRPath + '/R$*', 'META-INF/')
            }
            jarTask.onlyIf {
                File file = mProject.file(mProject.buildDir.absolutePath + '/intermediates/classes/' + mVariant.name.capitalize())
                return file.exists();
            }
            jarTask.doLast {
                println 'transform jar ready'
                File file = new File(mProject.getBuildDir().absolutePath + '/libs/' + mProject.name + '.jar');
                if (file.exists()) {
                    mProject.delete(classesJar)
                    mProject.copy {
                        from(file)
                        into(AndroidPluginHelper.resolveBundleDir(mProject, mVariant))
                        rename(mProject.name + '.jar', 'classes.jar')
                    }
                } else {
                    println 'can not find transformProguradJar file ';
                }
            }
        }


        bundleTask.dependsOn jarTask
        jarTask.shouldRunAfter(syncLibTask)
    }

    /**
     * delete  exclude files
     */
    private void processExcludeFiles() {
        String taskPath = 'bundle' + mVariant.name.capitalize()
        Task bundleTask = mProject.tasks.findByPath(taskPath)
        if (bundleTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        taskPath = 'transformClassesAndResourcesWithSyncLibJarsFor' + mVariant.name.capitalize()
        Task syncLibTask = mProject.tasks.findByPath(taskPath)
        if (syncLibTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        def excludeFileTask = mProject.tasks.create(name: 'transformExcludeFilesTask' + mVariant.name) << {
            def bundlePath = AndroidPluginHelper.resolveBundleDir(mProject, mVariant).path
            mExcludeFiles.each { excludeFile ->
                excludeFile.fileNames.each { fileName ->
                    File file = mProject.file(bundlePath + File.separator + excludeFile.name + File.separator + fileName)
                    println file.path
                    if (file.exists()) {
                        file.delete()
                    } else {
                        println 'excludeFileError : ' + file.path + ' not exist'
                    }
                }
            }
        }
        bundleTask.dependsOn excludeFileTask
        excludeFileTask.shouldRunAfter(syncLibTask)
    }
}