package de.difuture.ekut.pht.lib.runtime.api.docker.data

/**
 * Represents the creation of a Docker container
 *
 * @author Lukas Zimmermann
 */
data class DockerContainerCreation(

    val containerId: DockerContainerId,
    val warnings: List<String>
)
