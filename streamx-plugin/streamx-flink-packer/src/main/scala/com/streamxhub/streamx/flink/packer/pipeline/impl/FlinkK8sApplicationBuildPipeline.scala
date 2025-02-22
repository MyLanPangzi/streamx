/*
 * Copyright (c) 2019 The StreamX Project
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streamxhub.streamx.flink.packer.pipeline.impl

import com.github.dockerjava.api.command.PushImageCmd
import com.github.dockerjava.core.command.{HackBuildImageCmd, HackPullImageCmd, HackPushImageCmd}
import com.google.common.collect.Sets
import com.streamxhub.streamx.common.conf.CommonConfig.DOCKER_IMAGE_NAMESPACE
import com.streamxhub.streamx.common.conf.{ConfigHub, Workspace}
import com.streamxhub.streamx.common.enums.DevelopmentMode
import com.streamxhub.streamx.common.fs.LfsOperator
import com.streamxhub.streamx.common.util.ThreadUtils
import com.streamxhub.streamx.flink.kubernetes.PodTemplateTool
import com.streamxhub.streamx.flink.packer.docker._
import com.streamxhub.streamx.flink.packer.maven.MavenTool
import com.streamxhub.streamx.flink.packer.pipeline.BuildPipeline.executor
import com.streamxhub.streamx.flink.packer.pipeline._

import java.io.File
import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

/**
 * Building pipeline for flink kubernetes-native application mode
 *
 * @author Al-assad
 */
class FlinkK8sApplicationBuildPipeline(request: FlinkK8sApplicationBuildRequest) extends BuildPipeline {

  override def pipeType: PipelineType = PipelineType.FLINK_NATIVE_K8S_APPLICATION

  private var dockerProcessWatcher: DockerProgressWatcher = new SilentDockerProgressWatcher

  // non-thread-safe
  private val dockerProcess = new DockerResolveProgress(DockerPullProgress.empty(), DockerBuildProgress.empty(), DockerPushProgress.empty())

  override protected def offerBuildParam: FlinkK8sApplicationBuildRequest = request

  def registerDockerProgressWatcher(watcher: DockerProgressWatcher): Unit = {
    dockerProcessWatcher = watcher
  }

  @throws[Throwable] override protected def buildProcess(): DockerImageBuildResponse = {

    // Step-1: init build workspace of flink job
    // the sub workspace dir like: APP_WORKSPACE/k8s-clusterId@k8s-namespace/
    val buildWorkspace =
    execStep(1) {
      val buildWorkspace = s"${Workspace.local.APP_WORKSPACE}/${request.clusterId}@${request.k8sNamespace}"
      LfsOperator.mkCleanDirs(buildWorkspace)
      logInfo(s"recreate building workspace: $buildWorkspace")
      buildWorkspace
    }.getOrElse(throw getError.exception)

    // Step-2: export k8s pod template files
    val podTemplatePaths = request.flinkPodTemplate match {
      case podTemplate if podTemplate.isEmpty =>
        skipStep(2)
        Map[String, String]()
      case podTemplate =>
        execStep(2) {
          val podTemplateFiles = PodTemplateTool.preparePodTemplateFiles(buildWorkspace, podTemplate).tmplFiles
          logInfo(s"export flink podTemplates: ${podTemplateFiles.values.mkString(",")}")
          podTemplateFiles
        }.getOrElse(throw getError.exception)
    }

    // Step-3: build shaded flink job jar and handle extra jars
    // the output shaded jar file name like: streamx-flinkjob_myjob-test.jar
    val (shadedJar, extJarLibs) =
    execStep(3) {
      val appName = BuildPipelineHelper.getSafeAppName(request.appName)
      val shadedJarOutputPath = s"$buildWorkspace/streamx-flinkjob_$appName.jar"

      val providedLibs = BuildPipelineHelper.extractFlinkProvidedLibs(request)
      val depsInfoWithProvidedLibs = request.dependencyInfo.merge(providedLibs)
      val (shadedJar, extJarLibs) = request.developmentMode match {
        case DevelopmentMode.FLINKSQL =>
          val shadedJar = MavenTool.buildFatJar(request.mainClass, depsInfoWithProvidedLibs, shadedJarOutputPath)
          shadedJar -> request.dependencyInfo.extJarLibs

        case DevelopmentMode.CUSTOMCODE =>
          val shadedJar = MavenTool.buildFatJar(request.mainClass, depsInfoWithProvidedLibs, shadedJarOutputPath)
          shadedJar -> Set[String]()
      }
      logInfo(s"output shaded flink job jar: ${shadedJar.getAbsolutePath}")
      shadedJar -> extJarLibs
    }.getOrElse(throw getError.exception)

    // Step-4: generate and Export flink image dockerfiles
    val (dockerfile, dockerFileTemplate) =
      execStep(4) {
        val dockerFileTemplate = {
          if (request.integrateWithHadoop) {
            FlinkHadoopDockerfileTemplate.fromSystemHadoopConf(
              buildWorkspace,
              request.flinkBaseImage,
              shadedJar.getAbsolutePath,
              extJarLibs)
          } else {
            FlinkDockerfileTemplate(
              buildWorkspace,
              request.flinkBaseImage,
              shadedJar.getAbsolutePath,
              extJarLibs)
          }
        }
        val dockerFile = dockerFileTemplate.writeDockerfile
        logInfo(s"output flink dockerfile: ${dockerFile.getAbsolutePath}, content: \n${dockerFileTemplate.offerDockerfileContent}")
        dockerFile -> dockerFileTemplate
      }.getOrElse(throw getError.exception)

    val authConf = request.dockerAuthConfig
    val baseImageTag = request.flinkBaseImage
    val pushImageTag = {
      val expectedImageTag = s"streamxflinkjob-${request.k8sNamespace}-${request.clusterId}"
      compileTag(expectedImageTag, authConf.registerAddress)
    }

    // Step-5: pull flink base image
    execStep(5) {
      usingDockerClient {
        dockerClient =>
          val pullImageCmd = {
            // when the register address prefix is explicitly identified on base image tag,
            // the user's pre-saved docker register auth info would be used.
            if (!baseImageTag.startsWith(authConf.registerAddress)) {
              dockerClient.pullImageCmd(baseImageTag)
            } else {
              dockerClient.pullImageCmd(baseImageTag).withAuthConfig(authConf.toDockerAuthConf)
            }
          }
          val pullCmdCallback = pullImageCmd.asInstanceOf[HackPullImageCmd]
            .start(watchDockerPullProcess {
              pullRsp =>
                dockerProcess.pull.update(pullRsp)
                Future(dockerProcessWatcher.onDockerPullProgressChange(dockerProcess.pull.snapshot))
            })
          pullCmdCallback.awaitCompletion
          logInfo(s"already pulled docker image from remote register, imageTag=$baseImageTag")
      }(err => throw new Exception(s"pull docker image failed, imageTag=$baseImageTag", err))
    }.getOrElse(throw getError.exception)

    // Step-6: build flink image
    execStep(6) {
      usingDockerClient {
        dockerClient =>
          val buildImageCmd = dockerClient.buildImageCmd()
            .withBaseDirectory(new File(buildWorkspace))
            .withDockerfile(dockerfile)
            .withTags(Sets.newHashSet(pushImageTag))

          val buildCmdCallback = buildImageCmd.asInstanceOf[HackBuildImageCmd]
            .start(watchDockerBuildStep {
              buildStep =>
                dockerProcess.build.update(buildStep)
                Future(dockerProcessWatcher.onDockerBuildProgressChange(dockerProcess.build.snapshot))
            })
          val imageId = buildCmdCallback.awaitImageId
          logInfo(s"built docker image, imageId=$imageId, imageTag=$pushImageTag")
      }(err => throw new Exception(s"build docker image failed. tag=${pushImageTag}", err))
    }.getOrElse(throw getError.exception)

    // Step-7: push flink image
    execStep(7) {
      usingDockerClient {
        dockerClient =>
          val pushCmd: PushImageCmd = dockerClient
            .pushImageCmd(pushImageTag)
            .withAuthConfig(authConf.toDockerAuthConf)

          val pushCmdCallback = pushCmd.asInstanceOf[HackPushImageCmd]
            .start(watchDockerPushProcess {
              pushRsp =>
                dockerProcess.push.update(pushRsp)
                Future(dockerProcessWatcher.onDockerPushProgressChange(dockerProcess.push.snapshot))
            })
          pushCmdCallback.awaitCompletion
          logInfo(s"already pushed docker image, imageTag=$pushImageTag")
      }(err => throw new Exception(s"push docker image failed. tag=${pushImageTag}", err))
    }.getOrElse(throw getError.exception)

    DockerImageBuildResponse(buildWorkspace, pushImageTag, podTemplatePaths, dockerFileTemplate.innerMainJarPath)
  }


  /**
   * compile image tag with namespace and remote address.
   */
  private[this] def compileTag(tag: String, registerAddress: String): String = {
    val imgNamespace: String = ConfigHub.get(DOCKER_IMAGE_NAMESPACE)
    var tagName = if (tag.contains("/")) tag else s"$imgNamespace/$tag"
    if (registerAddress.nonEmpty && !tagName.startsWith(registerAddress)) {
      tagName = s"$registerAddress/$tagName"
    }
    tagName.toLowerCase
  }

}

object FlinkK8sApplicationBuildPipeline {

  val execPool = new ThreadPoolExecutor(
    Runtime.getRuntime.availableProcessors * 2,
    300,
    60L,
    TimeUnit.SECONDS,
    new LinkedBlockingQueue[Runnable](2048),
    ThreadUtils.threadFactory("streamx-docker-progress-watcher-executor"),
    new ThreadPoolExecutor.DiscardOldestPolicy
  )

  implicit val executor: ExecutionContext = ExecutionContext.fromExecutorService(execPool)

  def of(request: FlinkK8sApplicationBuildRequest): FlinkK8sApplicationBuildPipeline = new FlinkK8sApplicationBuildPipeline(request)

}
