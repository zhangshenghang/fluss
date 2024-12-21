---
sidebar_label: Logging
sidebar_position: 6
---

# Logging

All Fluss processes(CoordinatorServer/TabletServer) create a log text file that contains messages for various events happening in that process. These logs provide deep insights into the inner workings of Fluss, and can be used to detect problems (in the form of WARN/ERROR messages) and can help in debugging them.

The logging in Fluss uses the [SLF4J](http://www.slf4j.org/) logging interface. This allows you to use any logging framework that supports SLF4J, without having to modify the Fluss source code.

By default, [Log4j 2](https://logging.apache.org/log4j/2.x/index.html) is used as the underlying logging framework.

## Configuring Log4j 2
### Log4j 2 property files
The Fluss distribution ships with the following log4j properties files in the conf directory, which are used automatically if Log4j 2 is enabled:
* `log4j-console.properties`: used for CoordinatorServer/TabletServer if they are run in the foreground (e.g., Kubernetes).
* `log4j.properties`: used for CoordinatorServer/TabletServer by default.

Log4j periodically scans this file for changes and adjusts the logging behavior if necessary. By default, this check happens every 30 seconds and is controlled by the monitorInterval setting in the Log4j properties files.


### Log4j 2 configuration
The following [logging-related configuration options](./configuration.md) are available:

| Configuration                   | Description                                                             | Default                        |
|---------------------------------|-------------------------------------------------------------------------|--------------------------------|
| env.log.dir                     | The directory where the Fluss logs are saved. Must be an absolute path. | log folder under Fluss’s home. |
| env.log.level                   | Root logger level.                                                      | INFO                           |
| env.log.max                     | The maximum number of old log files to keep.                            | 10                             |
| env.stdout-err.redirect-to-file | Whether to redirect the ERROR level Fluss logs to another file.         | false                          |

### Compatibility with Log4j 1
Fluss ships with the [Log4j API bridge](https://logging.apache.org/log4j/log4j-2.2/log4j-1.2-api/index.html), allowing existing applications that work against Log4j1 classes to continue working.

If you have custom Log4j 1 properties files or code that relies on Log4j 1, please check out the official Log4j [compatibility](https://logging.apache.org/log4j/2.x/manual/compatibility.html) and [migration](https://logging.apache.org/log4j/2.x/manual/migration.html) guides.

## Configuring Log4j1
To use Fluss with [Log4j 1](https://logging.apache.org/log4j/1.2/) you must ensure that:
* `org.apache.logging.log4j:log4j-core`, `org.apache.logging.log4j:log4j-slf4j-impl` and `org.apache.logging.log4j:log4j-1.2-api` are not on the classpath.
* `log4j:log4j`, `org.slf4j:slf4j-log4j12`, `org.apache.logging.log4j:log4j-to-slf4j` and `org.apache.logging.log4j:log4j-api` are on the classpath.

For Fluss distributions this means you have to:
* remove the `log4j-core`, `log4j-slf4j-impl` and `log4j-1.2-api` jars from the lib directory,
* add the `log4j`, `slf4j-log4j12` and `log4j-to-slf4j` jars to the lib directory,
* replace all log4j properties files in the conf directory with Log4j1-compliant versions.

In the IDE this means you have to replace such dependencies defined in your pom, and possibly add exclusions on dependencies that transitively depend on them.

## Configuring logback
To use Fluss with [logback](https://logback.qos.ch/) you must ensure that:
* `org.apache.logging.log4j:log4j-slf4j-impl` is not on the classpath,
* `ch.qos.logback:logback-core` and `ch.qos.logback:logback-classic` are on the classpath.

For Fluss distributions this means you have to:
* remove the `log4j-slf4j-impl` jar from the lib directory.
* add the `logback-core`, and `logback-classic` jars to the lib directory.

The Fluss distribution ships with the following logback configuration files in the conf directory, which are used automatically if logback is enabled:
* `logback-console.xml`: used for CoordinatorServer/TabletServer if they are run in the foreground (e.g., Kubernetes).
* `logback.xml`: used for CoordinatorServer/TabletServer by default.

In the IDE this means you have to replace such dependencies defined in your pom, and possibly add exclusions on dependencies that transitively depend on them.


