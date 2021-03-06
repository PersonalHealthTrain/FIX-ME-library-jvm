package de.difuture.ekut.pht.lib.runtime.impl

import de.difuture.ekut.pht.lib.runtime.api.docker.DockerRuntimeClient
import de.difuture.ekut.pht.lib.runtime.api.docker.data.DockerContainerCreation
import de.difuture.ekut.pht.lib.runtime.api.docker.data.DockerRunOptionalParameters
import dockerdaemon.data.DockerContainerId
import dockerdaemon.data.DockerContainerOutput
import dockerdaemon.data.DockerImageId
import dockerdaemon.data.DockerNetworkReference
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import jdregistry.client.data.RepositoryName as DockerRepositoryName
import jdregistry.client.data.Tag as DockerTag
import java.nio.file.Path

/**
 * Spotify-client-based implementation of the [DockerRuntimeClient] interface.
 *
 * This implementation encapsulates completely the base Docker client related properties.
 * For instance, all Spotify related exceptions are converted to exceptions from the library
 *
 * @author Lukas Zimmermann
   @see DockerRuntimeClient
 * @since 0.0.1
 *
 */
abstract class AbstractDockerRuntimeClient : DockerRuntimeClient {

    protected var closed = false

    final override fun commitByRebase(
        containerId: DockerContainerId,
        exportFiles: List<Path>,
        from: String,
        targetRepo: DockerRepositoryName,
        targetTag: DockerTag
    ): DockerImageId {

        if (closed) {
            throw IllegalStateException("Docker Client has been closed")
        }

        // Guard: check that all paths in the input are absolute
        exportFiles.firstOrNull { ! it.isAbsolute }?.run {
            throw IllegalArgumentException("Export Path not absolute: $this")
        }

        // 1. First, create a new container from the baseImage in which the files should be copied into
        // currently, only pulling from Docker Hub is allowed
        val targetContainerId = createContainer(repoTagToImageId(from, pullMode = PullMode.PUBLIC)).containerId

        // 2. Copy all the files from the source container into the target container
        for (path in exportFiles) {
            containerCopyFile(path, from = containerId, to = targetContainerId)
        }

        // 3. Create the new image from the container
        commitContainer(containerId, targetRepo, targetTag)

        // 4 Remove the created containers
        stopAndRemoveContainer(targetContainerId)
        stopAndRemoveContainer(containerId)
        return repoTagToImageId(targetRepo.resolve(targetTag), pullMode = PullMode.NO_PULL)
    }

    override fun run(
        imageId: DockerImageId,
        commands: List<String>,
        rm: Boolean,
        optionalParams: DockerRunOptionalParameters?
    ): DockerContainerOutput {

        if (closed) {
            throw IllegalStateException("Docker client has been closed")
        }

        // We rethrow ImageNotFound and DockerClient exceptions, but let Interrupted Exceptions pass throw
        // TODO Platform type. Can this be null? This would not be documented by the method
        // TODO Network
        val creation = createContainer(imageId, commands, optionalParams?.env)
        val containerId = creation.containerId

        // Now start the container
        startContainer(containerId)

        // The Interrupt needs to be handled after the container has been started
        val interruptSignaler = optionalParams?.interruptSignaler
        val interruptHandler = optionalParams?.interruptHandler

        if (interruptSignaler != null && interruptHandler != null) {
            while (isRunning(containerId)) {
                if (interruptSignaler.wasInterrupted(containerId)) {
                    interruptHandler.handleInterrupt(containerId)
                }
            }
        }
        // Now fetch the container exit
        val exit = waitForContainer(containerId)

        // Stdout and Stderr need to be read before the container is gonna be removed
        val stdout = getStdout(containerId)
        val stderr = getStderr(containerId)

        // Remove the container if this was requested
        if (rm) {
            stopAndRemoveContainer(containerId)
        }

        return DockerContainerOutput(
            containerId,
            exit,
            stdout,
            stderr,
            creation.warnings)
    }

    override fun pull(repo: DockerRepositoryName, tag: DockerTag, host: String?): DockerImageId =
            this.repoTagToImageId(repo.resolve(tag, host), pullMode = PullMode.AUTH)

    abstract fun createContainer(
        imageId: DockerImageId,
        commands: List<String>? = null,
        env: Map<String, String>? = null,
        network: DockerNetworkReference? = null
    ): DockerContainerCreation

    abstract fun repoTagToImageId(repoTag: String, pullMode: PullMode): DockerImageId

    abstract fun startContainer(containerId: DockerContainerId)

    /**
     * Copies a file via path from the 'from' container to the 'to' container.
     * Path must refer to an regular file
     */
    abstract fun containerCopyFile(path: Path, from: DockerContainerId, to: DockerContainerId)

    abstract fun commitContainer(containerId: DockerContainerId, targetRepo: DockerRepositoryName, targetTag: DockerTag)

    abstract fun stopAndRemoveContainer(containerId: DockerContainerId)

    abstract fun isRunning(containerId: DockerContainerId): Boolean

    abstract fun waitForContainer(containerId: DockerContainerId): Int

    abstract fun getStdout(containerId: DockerContainerId): String

    abstract fun getStderr(containerId: DockerContainerId): String

    enum class PullMode {
        NO_PULL,
        PUBLIC,
        AUTH
    }
}
