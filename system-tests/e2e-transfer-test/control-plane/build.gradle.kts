/*
 *  Copyright (c) 2022 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":dist:bom:controlplane-base-bom"))
    implementation(project(":extensions:common:iam:iam-mock"))
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // Forward all -Dedc.* system properties from Gradle to the forked JVM
    systemProperties(System.getProperties().filter { (k, _) -> k.toString().startsWith("edc.") }
        .map { (k, v) -> k.toString() to v.toString() }.toMap())
}

edcBuild {
    publish.set(false)
}
