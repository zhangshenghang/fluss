---
sidebar_position: 1
---

# Building Fluss from Source

This page covers how to build Fluss 0.6.0-SNAPSHOT from sources.

## Build Fluss

In order to build Fluss you need to get the source code by [clone the git repository](https://github.com/alibaba/fluss).

In addition, you need **Maven 3.8.6** and a **JDK** (Java Development Kit). Fluss requires **Java 8 or Java 11** to build.

To clone from git, enter:

```bash
git clone git@github.com:alibaba/fluss.git
```

The simplest way of building Fluss is by running:

```bash
mvn clean install -DskipTests
```

This instructs [Maven](http://maven.apache.org) (`mvn`) to first remove all existing builds (`clean`) and then create a new Fluss binary (`install`).

To speed up the build you can:
- skip tests by using ` -DskipTests`
- use Maven's parallel build feature, e.g., `mvn package -T 1C` will attempt to build 1 module for each CPU core in parallel.

The build script will be:
```bash
mvn clean install -DskipTests -T 1C
```