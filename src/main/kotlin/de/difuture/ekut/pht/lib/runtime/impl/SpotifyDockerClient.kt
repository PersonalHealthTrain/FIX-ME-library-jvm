package de.difuture.ekut.pht.lib.runtime.impl

import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.messages.ContainerConfig
import com.spotify.docker.client.messages.RegistryAuth
import de.difuture.ekut.pht.lib.runtime.api.docker.CreateDockerContainerFailedException
import de.difuture.ekut.pht.lib.runtime.api.docker.data.DockerContainerCreation
import dockerdaemon.data.DockerContainerId
import dockerdaemon.data.DockerImageId
import dockerdaemon.data.DockerNetworkReference
import kotlinext.map.asKeyValueList
import java.nio.file.Path
import jdregistry.client.data.RepositoryName as DockerRepositoryName
import jdregistry.client.data.Tag as DockerTag

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
class SpotifyDockerClient : AbstractDockerRuntimeClient() {

    private val baseClient = DefaultDockerClient.fromEnv().build()
    private var auth: RegistryAuth? = null

    override fun createContainer(
        imageId: DockerImageId,
        commands: List<String>?,
        env: Map<String, String>?,
        network: DockerNetworkReference?
    ): DockerContainerCreation {

        // Config
        var config = ContainerConfig.builder().image(imageId.repr)
        if (commands != null) {
            config = config.cmd(commands)
        }
        if (env != null) {
            config = config.env(env.asKeyValueList())
        }

        // Creation
        val creation = baseClient.createContainer(config.build())
        val containerId = creation.id()?.let { DockerContainerId(it) }
            ?: throw CreateDockerContainerFailedException("Creation of container failed")

        // Network, container must not be started before connecting
        if (network != null) {
            baseClient.connectToNetwork(containerId.repr, network.repr)
        }
        return DockerContainerCreation(containerId, creation.warnings().orEmpty())
    }

    override fun repoTagToImageId(
        repoTag: String,
        pullMode: PullMode
    ): DockerImageId {

        // Pull depening on the Pull Mode
        when (pullMode) {
            PullMode.AUTH -> if (auth != null) {
                baseClient.pull(repoTag, auth)
            } else {
                baseClient.pull(repoTag)
            }
            PullMode.PUBLIC ->
                baseClient.pull(repoTag)
            else -> {}
        }

        val images = baseClient.listImages().filter {

            val repoTags = it.repoTags()
            repoTags != null && repoTag in repoTags
        }
        return images.singleOrNull()?.let { DockerImageId(it.id()) }
                ?: throw IllegalStateException("Implementation Error! Zero or more than one image found for $repoTag")
    }

    override fun startContainer(containerId: DockerContainerId) {
        baseClient.startContainer(containerId.repr)
    }

    override fun containerCopyFile(path: Path, from: DockerContainerId, to: DockerContainerId) {
        val file = path.toFile()
        require(path.isAbsolute) {
            "Input Path $file is not absolute"
        }
        val parent = file.parentFile.absolutePath
        baseClient.archiveContainer(from.repr, file.absolutePath).use {
            baseClient.copyToContainer(it, to.repr, parent)
        }
//
//        baseClient.archiveContainer(containerId.repr, file.absolutePath).use { inputStream ->
//            baseClient.copyToContainer(inputStream, targetContainerId, file.parentFile.absolutePath)
    }

    override fun commitContainer(
        containerId: DockerContainerId,
        targetRepo: jdregistry.client.data.RepositoryName,
        targetTag: jdregistry.client.data.Tag
    ) {
        baseClient.commitContainer(
            containerId.repr,
            targetRepo.repr,
            targetTag.repr,
            ContainerConfig.builder().build(), null, null)
    }

    override fun stopAndRemoveContainer(containerId: DockerContainerId) {
        baseClient.stopContainer(containerId.repr, 10)
        baseClient.removeContainer(containerId.repr)
    }

    override fun isRunning(containerId: DockerContainerId): Boolean {
        val container = baseClient.listContainers().first { it.id() == containerId.repr }
        // TODO NPE if container does not exist
        return container.status() != "exited"
    }

    override fun waitForContainer(containerId: DockerContainerId): Int =
        baseClient.waitContainer(containerId.repr).statusCode().toInt()

    override fun getStdout(containerId: DockerContainerId): String =
        baseClient.logs(containerId.repr, DockerClient.LogsParam.stdout()).readFully()

    override fun getStderr(containerId: DockerContainerId): String =
        baseClient.logs(containerId.repr, DockerClient.LogsParam.stderr()).readFully()

    override fun push(repo: DockerRepositoryName, tag: DockerTag, host: String?) {
        val repoTag = repo.resolve(tag, host)
        if (auth != null) {
            baseClient.push(repoTag, auth)
        } else {
            baseClient.push(repoTag)
        }
    }

    override fun tag(
        imageId: DockerImageId,
        targetRepo: jdregistry.client.data.RepositoryName,
        targetTag: jdregistry.client.data.Tag,
        host: String?
    ) {
        baseClient.tag(imageId.repr, targetRepo.resolve(targetTag, host))
    }

    override fun login(username: String, password: String, host: String?): Boolean {
        val builder = RegistryAuth.builder().username(username).password(password)
        val auth = (host?.let { builder.serverAddress(it) } ?: builder).build()
        return if (baseClient.auth(auth) == 200) {
            this.auth = auth
            true
        } else false
    }

    override fun close() {
        this.closed = true
        baseClient.close()
    }
}
