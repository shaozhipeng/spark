/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.scheduler.cluster.k8s

import scala.collection.JavaConverters._

import io.fabric8.kubernetes.api.model._
import org.mockito.MockitoAnnotations
import org.scalatest.{BeforeAndAfter, BeforeAndAfterEach}

import org.apache.spark.{SparkConf, SparkFunSuite}
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.MountSecretsBootstrap

class ExecutorPodFactorySuite extends SparkFunSuite with BeforeAndAfter with BeforeAndAfterEach {

  private val driverPodName: String = "driver-pod"
  private val driverPodUid: String = "driver-uid"
  private val executorPrefix: String = "base"
  private val executorImage: String = "executor-image"
  private val driverPod = new PodBuilder()
    .withNewMetadata()
    .withName(driverPodName)
    .withUid(driverPodUid)
    .endMetadata()
    .withNewSpec()
    .withNodeName("some-node")
    .endSpec()
    .withNewStatus()
    .withHostIP("192.168.99.100")
    .endStatus()
    .build()
  private var baseConf: SparkConf = _

  before {
    MockitoAnnotations.initMocks(this)
    baseConf = new SparkConf()
      .set(KUBERNETES_DRIVER_POD_NAME, driverPodName)
      .set(KUBERNETES_EXECUTOR_POD_NAME_PREFIX, executorPrefix)
      .set(CONTAINER_IMAGE, executorImage)
      .set(KUBERNETES_DRIVER_SUBMIT_CHECK, true)
  }

  test("basic executor pod has reasonable defaults") {
    val factory = new ExecutorPodFactory(baseConf, None)
    val executor = factory.createExecutorPod(
      "1", "dummy", "dummy", Seq[(String, String)](), driverPod, Map[String, Int]())

    // The executor pod name and default labels.
    assert(executor.getMetadata.getName === s"$executorPrefix-exec-1")
    assert(executor.getMetadata.getLabels.size() === 3)
    assert(executor.getMetadata.getLabels.get(SPARK_EXECUTOR_ID_LABEL) === "1")

    // There is exactly 1 container with no volume mounts and default memory limits and requests.
    // Default memory limit/request is 1024M + 384M (minimum overhead constant).
    assert(executor.getSpec.getContainers.size() === 1)
    assert(executor.getSpec.getContainers.get(0).getImage === executorImage)
    assert(executor.getSpec.getContainers.get(0).getVolumeMounts.isEmpty)
    assert(executor.getSpec.getContainers.get(0).getResources.getLimits.size() === 1)
    assert(executor.getSpec.getContainers.get(0).getResources
      .getRequests.get("memory").getAmount === "1408Mi")
    assert(executor.getSpec.getContainers.get(0).getResources
      .getLimits.get("memory").getAmount === "1408Mi")

    // The pod has no node selector, volumes.
    assert(executor.getSpec.getNodeSelector.isEmpty)
    assert(executor.getSpec.getVolumes.isEmpty)

    checkEnv(executor, Map())
    checkOwnerReferences(executor, driverPodUid)
  }

  test("executor core request specification") {
    var factory = new ExecutorPodFactory(baseConf, None)
    var executor = factory.createExecutorPod(
      "1", "dummy", "dummy", Seq[(String, String)](), driverPod, Map[String, Int]())
    assert(executor.getSpec.getContainers.size() === 1)
    assert(executor.getSpec.getContainers.get(0).getResources.getRequests.get("cpu").getAmount
      === "1")

    val conf = baseConf.clone()

    conf.set(KUBERNETES_EXECUTOR_REQUEST_CORES, "0.1")
    factory = new ExecutorPodFactory(conf, None)
    executor = factory.createExecutorPod(
      "1", "dummy", "dummy", Seq[(String, String)](), driverPod, Map[String, Int]())
    assert(executor.getSpec.getContainers.size() === 1)
    assert(executor.getSpec.getContainers.get(0).getResources.getRequests.get("cpu").getAmount
      === "0.1")

    conf.set(KUBERNETES_EXECUTOR_REQUEST_CORES, "100m")
    factory = new ExecutorPodFactory(conf, None)
    conf.set(KUBERNETES_EXECUTOR_REQUEST_CORES, "100m")
    executor = factory.createExecutorPod(
      "1", "dummy", "dummy", Seq[(String, String)](), driverPod, Map[String, Int]())
    assert(executor.getSpec.getContainers.get(0).getResources.getRequests.get("cpu").getAmount
      === "100m")
  }

  test("executor pod hostnames get truncated to 63 characters") {
    val conf = baseConf.clone()
    conf.set(KUBERNETES_EXECUTOR_POD_NAME_PREFIX,
      "loremipsumdolorsitametvimatelitrefficiendisuscipianturvixlegeresple")

    val factory = new ExecutorPodFactory(conf, None)
    val executor = factory.createExecutorPod(
      "1", "dummy", "dummy", Seq[(String, String)](), driverPod, Map[String, Int]())

    assert(executor.getSpec.getHostname.length === 63)
  }

  test("classpath and extra java options get translated into environment variables") {
    val conf = baseConf.clone()
    conf.set(org.apache.spark.internal.config.EXECUTOR_JAVA_OPTIONS, "foo=bar")
    conf.set(org.apache.spark.internal.config.EXECUTOR_CLASS_PATH, "bar=baz")

    val factory = new ExecutorPodFactory(conf, None)
    val executor = factory.createExecutorPod(
      "1", "dummy", "dummy", Seq[(String, String)]("qux" -> "quux"), driverPod, Map[String, Int]())

    checkEnv(executor,
      Map("SPARK_JAVA_OPT_0" -> "foo=bar",
        ENV_CLASSPATH -> "bar=baz",
        "qux" -> "quux"))
    checkOwnerReferences(executor, driverPodUid)
  }

  test("executor secrets get mounted") {
    val conf = baseConf.clone()

    val secretsBootstrap = new MountSecretsBootstrap(Map("secret1" -> "/var/secret1"))
    val factory = new ExecutorPodFactory(conf, Some(secretsBootstrap))
    val executor = factory.createExecutorPod(
      "1", "dummy", "dummy", Seq[(String, String)](), driverPod, Map[String, Int]())

    assert(executor.getSpec.getContainers.size() === 1)
    assert(executor.getSpec.getContainers.get(0).getVolumeMounts.size() === 1)
    assert(executor.getSpec.getContainers.get(0).getVolumeMounts.get(0).getName
      === "secret1-volume")
    assert(executor.getSpec.getContainers.get(0).getVolumeMounts.get(0)
      .getMountPath === "/var/secret1")

    // check volume mounted.
    assert(executor.getSpec.getVolumes.size() === 1)
    assert(executor.getSpec.getVolumes.get(0).getSecret.getSecretName === "secret1")

    checkOwnerReferences(executor, driverPodUid)
  }

  // There is always exactly one controller reference, and it points to the driver pod.
  private def checkOwnerReferences(executor: Pod, driverPodUid: String): Unit = {
    assert(executor.getMetadata.getOwnerReferences.size() === 1)
    assert(executor.getMetadata.getOwnerReferences.get(0).getUid === driverPodUid)
    assert(executor.getMetadata.getOwnerReferences.get(0).getController === true)
  }

  // Check that the expected environment variables are present.
  private def checkEnv(executor: Pod, additionalEnvVars: Map[String, String]): Unit = {
    val defaultEnvs = Map(
      ENV_EXECUTOR_ID -> "1",
      ENV_DRIVER_URL -> "dummy",
      ENV_EXECUTOR_CORES -> "1",
      ENV_EXECUTOR_MEMORY -> "1g",
      ENV_APPLICATION_ID -> "dummy",
      ENV_SPARK_CONF_DIR -> SPARK_CONF_DIR_INTERNAL,
      ENV_EXECUTOR_POD_IP -> null) ++ additionalEnvVars

    assert(executor.getSpec.getContainers.size() === 1)
    assert(executor.getSpec.getContainers.get(0).getEnv.size() === defaultEnvs.size)
    val mapEnvs = executor.getSpec.getContainers.get(0).getEnv.asScala.map {
      x => (x.getName, x.getValue)
    }.toMap
    assert(defaultEnvs === mapEnvs)
  }
}
