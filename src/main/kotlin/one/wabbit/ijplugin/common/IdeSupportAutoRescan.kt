// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.ijplugin.common

import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import java.nio.file.Path

object IdeSupportAutoRescan {
    fun scheduleInitialRescan(project: Project, requestRescan: () -> Unit) {
        scheduleInitialRescan(project = project, requestRescan = requestRescan) { callback ->
            backgroundCallback(project = project, callback = callback)
        }
    }

    fun install(
        project: Project,
        requestRescan: () -> Unit,
        requestGradleImportFinishedRescan: () -> Unit,
    ) {
        val projectConnection = project.messageBus.connect(project)
        val applicationConnection = ApplicationManager.getApplication().messageBus.connect(project)
        var watchedProjectRootsCache: List<Path>? = null
        val invalidateWatchedProjectRootsCache = { watchedProjectRootsCache = null }
        val readCachedWatchedProjectRoots = {
            watchedProjectRootsCache
                ?: readWatchedProjectRoots(project).also { watchedProjectRootsCache = it }
        }
        install(
            project = project,
            requestRescan = requestRescan,
            requestGradleImportFinishedRescan = requestGradleImportFinishedRescan,
            subscribeImportFinished = { callback ->
                projectConnection.subscribe(
                    ProjectDataImportListener.TOPIC,
                    object : ProjectDataImportListener {
                        override fun onImportFinished(projectPath: String?) {
                            callback()
                        }
                    },
                )
            },
            subscribeRootsChanged = { callback ->
                projectConnection.subscribe(
                    ModuleRootListener.TOPIC,
                    object : ModuleRootListener {
                        override fun rootsChanged(event: ModuleRootEvent) {
                            invalidateWatchedProjectRootsCache()
                            callback()
                        }
                    },
                )
            },
            subscribeProjectTrusted = { callback ->
                applicationConnection.subscribe(
                    TrustedProjectsListener.TOPIC,
                    object : TrustedProjectsListener {
                        override fun onProjectTrusted(project: Project) {
                            callback(project)
                        }
                    },
                )
            },
            subscribeBuildFilesChanged = { callback ->
                applicationConnection.subscribe(
                    VirtualFileManager.VFS_CHANGES,
                    object : BulkFileListener {
                        override fun after(events: List<VFileEvent>) {
                            if (
                                shouldRescanForBuildFileChanges(
                                    changedPaths = events.map(VFileEvent::getPath),
                                    readWatchedRoots = readCachedWatchedProjectRoots,
                                )
                            ) {
                                invalidateWatchedProjectRootsCache()
                                callback()
                            }
                        }
                    },
                )
            },
            debounceCallback = { callback ->
                debouncedCallback(project = project, callback = callback)
            },
        )
    }

    fun install(
        project: Project,
        requestRescan: () -> Unit,
        requestGradleImportFinishedRescan: () -> Unit,
        subscribeImportFinished: ((() -> Unit)) -> Unit,
        subscribeRootsChanged: ((() -> Unit)) -> Unit,
        subscribeProjectTrusted: (((Project) -> Unit)) -> Unit,
        subscribeBuildFilesChanged: ((() -> Unit)) -> Unit = {},
        debounceCallback: (((() -> Unit)) -> (() -> Unit)) = { callback -> callback },
    ) {
        val debouncedRescan = debounceCallback(requestRescan)
        val debouncedImportFinishedRescan = debounceCallback(requestGradleImportFinishedRescan)
        subscribeImportFinished(debouncedImportFinishedRescan)
        subscribeRootsChanged(debouncedRescan)
        subscribeBuildFilesChanged(debouncedRescan)
        subscribeProjectTrusted { trustedProject ->
            if (trustedProject === project) {
                debouncedRescan()
            }
        }
    }

    internal fun scheduleInitialRescan(
        project: Project,
        requestRescan: () -> Unit,
        scheduleCallback: (((() -> Unit)) -> (() -> Unit)),
    ) {
        scheduleCallback(requestRescan).invoke()
    }

    private fun debouncedCallback(project: Project, callback: () -> Unit): () -> Unit {
        val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
        return {
            alarm.cancelAllRequests()
            alarm.addRequest(callback, 100)
        }
    }

    private fun backgroundCallback(project: Project, callback: () -> Unit): () -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed) {
                callback()
            }
        }
    }

    internal fun shouldRescanForBuildFileChanges(
        changedPaths: Iterable<String>,
        readWatchedRoots: () -> Iterable<Path>,
    ): Boolean {
        val candidateChangedPaths =
            changedPaths.filter(::isWatchedBuildFilePath).distinct()
        if (candidateChangedPaths.isEmpty()) {
            return false
        }
        return shouldRescanForBuildFileChanges(
            watchedRoots = readWatchedRoots(),
            changedPaths = candidateChangedPaths,
        )
    }

    internal fun shouldRescanForBuildFileChanges(
        watchedRoots: Iterable<Path>,
        changedPaths: Iterable<String>,
    ): Boolean {
        val normalizedRoots =
            watchedRoots.map { root -> root.toAbsolutePath().normalize() }.distinct()
        if (normalizedRoots.isEmpty()) {
            return false
        }
        return changedPaths.any { changedPath ->
            val changedFile =
                runCatching { Path.of(changedPath).toAbsolutePath().normalize() }.getOrNull()
                    ?: return@any false
            isWatchedBuildFileName(changedFile.fileName?.toString()) &&
                normalizedRoots.any { root -> changedFile.startsWith(root) }
        }
    }

    private fun isWatchedBuildFilePath(changedPath: String): Boolean =
        isWatchedBuildFileName(
            changedPath.substringAfterLast('/').substringAfterLast('\\')
        )

    private fun isWatchedBuildFileName(fileName: String?): Boolean {
        fileName ?: return false
        return fileName.endsWith(".gradle") ||
            fileName.endsWith(".gradle.kts") ||
            fileName.endsWith(".versions.toml")
    }

    private fun readWatchedProjectRoots(project: Project): List<Path> =
        CompilerPluginDetector.watchedGradleRoots(
            ReadAction.compute<List<Path>, RuntimeException> {
                buildList {
                        project.basePath?.let { add(Path.of(it)) }
                        ModuleManager.getInstance(project).modules.forEach { module ->
                            ModuleRootManager.getInstance(module).contentRoots.forEach { root ->
                                add(Path.of(root.path))
                            }
                        }
                    }
                    .map { root -> root.toAbsolutePath().normalize() }
                    .distinct()
            }
        )
}
