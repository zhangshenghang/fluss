---
sidebar_position: 2
---

# Import Fluss into an IDE

The sections below describe how to import the Fluss project into an IDE
for the development of Fluss itself.

:::note
Whenever something is not working in your IDE, try with the Maven
command line first (`mvn clean package -DskipTests`) as it might be your IDE
that has a bug or is not properly set up.
:::

## Preparation

To get started, please first checkout the Fluss sources from our
[repository](https://github.com/alibaba/fluss),
e.g.

```bash
git clone git@github.com:alibaba/fluss.git
```

## IntelliJ IDEA

The following guide has been written for [IntelliJ IDEA](https://www.jetbrains.com/idea/download/)
2024.3. Some details might differ in other versions. Please make sure to follow all steps

### Importing Fluss

1. Choose "New" → "Project from Existing Sources".
2. Select the root folder of the cloned Fluss repository.
3. Choose "Import project from external model" and select "Maven".
4. Leave the default options and successively click "Next" until you reach the SDK section.
5. If there is no SDK listed, create one using the "+" sign on the top left.
   Select "JDK", choose the JDK home directory and click "OK".
   Select the most suitable JDK version. NOTE: A good rule of thumb is to select
   the JDK version matching the active Maven profile.
6. Continue by clicking "Next" until the import is finished.
7. Open the "Maven" tab (or right-click on the imported project and find "Maven") and run
   "Generate Sources and Update Folders". Alternatively, you can run
   `mvn clean package -DskipTests`.
8. Build the Project ("Build" → "Build Project").

### Copyright Profile

Every file needs to include the Alibaba license as a header. This can be automated in IntelliJ by
adding a Copyright profile:

1. Go to "Settings" → "Editor" → "Copyright" → "Copyright Profiles".
2. Add a new profile and name it "Alibaba".
3. Add the following text as the license text:

   ```
    Copyright (c) 2024 Alibaba Group Holding Ltd.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at
    
        http://www.apache.org/licenses/LICENSE-2.0
   
    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
   ```
4. Go to "Editor" → "Copyright" and choose the "Alibaba" profile as the default profile for this
   project.
5. Click "Apply".

### Required Plugins

Go to "Settings" → "Plugins" and select the "Marketplace" tab. Search for the following plugins,
install them, and restart the IDE if prompted:

* [Save Actions X](https://plugins.jetbrains.com/plugin/22113-save-actions-x)
* [Checkstyle-IDEA](https://plugins.jetbrains.com/plugin/1065-checkstyle-idea)

You will also need to install the [google-java-format](https://github.com/google/google-java-format)
plugin. However, a specific version of this plugin is required. Download
[google-java-format v1.7.0.6](https://plugins.jetbrains.com/plugin/8527-google-java-format/versions/stable/115957)
and install it as follows. **Make sure to never update this plugin**.

1. Go to "Settings" → "Plugins".
2. Click the gear icon and select "Install Plugin from Disk".
3. Navigate to the downloaded ZIP file and select it.

#### Code Formatting

Fluss uses [Spotless](https://github.com/diffplug/spotless/tree/main/plugin-maven) together with
[google-java-format](https://github.com/google/google-java-format) to format the Java code.

It is recommended to automatically format your code by applying the following settings:

1. Go to "Settings" → "Other Settings" → "google-java-format Settings".
2. Tick the checkbox to enable the plugin.
3. Change the code style to "Android Open Source Project (AOSP) style".
6. Go to "Settings" → "Tools" → "Actions on Save".
7. Under "Formatting Actions", select "Optimize imports" and "Reformat code".
8. From the "All file types list" next to "Reformat code", select Java and Scala.

For earlier IntelliJ IDEA versions:

6. Go to "Settings" → "Other Settings" → "Save Actions".
7. Under "General", enable your preferred settings for when to format the code, e.g. "Activate save actions on save".
8. Under "Formatting Actions", select "Optimize imports" and "Reformat file".
9. Under "File Path Inclusions", add an entry for `.*\.java` to avoid formatting other file types.

You can also format the whole project via Maven by using `mvn spotless:apply`.


#### Checkstyle For Java

[Checkstyle](https://checkstyle.sourceforge.io/) is used to enforce static coding guidelines.

1. Go to "Settings" → "Tools" → "Checkstyle".
2. Set "Scan Scope" to "Only Java sources (including tests)".
3. For "Checkstyle Version" select "9.3".
4. Under "Configuration File" click the "+" icon to add a new configuration.
5. Set "Description" to "Fluss".
6. Select "Use a local Checkstyle file" and point it to `tools/maven/checkstyle.xml` located within
   your cloned repository.
7. Select "Store relative to project location" and click "Next".
8. Configure the property `checkstyle.suppressions.file` with the value `suppressions.xml` and click
   "Next".
9. Click "Finish".
10. Select "Fluss" as the only active configuration file and click "Apply".

You can now import the Checkstyle configuration for the Java code formatter.

1. Go to "Settings" → "Editor" → "Code Style" → "Java".
2. Click the gear icon next to "Scheme" and select "Import Scheme" → "Checkstyle Configuration".
3. Navigate to and select `tools/maven/checkstyle.xml` located within your cloned repository.

To verify the setup, click "View" → "Tool Windows" → "Checkstyle" and find the "Check Module"
button in the opened tool window. It should report no violations.

### Common Problems

This section lists issues that developers have run into in the past when working with IntelliJ.

#### Compilation fails with `invalid flag: --add-exports=java.base/sun.net.util=ALL-UNNAMED`

This happens if the "java11" Maven profile is active, but an older JDK version is used. Go to
"View" → "Tool Windows" → "Maven" and uncheck the "java11" profile. Afterwards, reimport the
project.

#### Compilation fails with `package sun.misc does not exist`

This happens if you are using JDK 11 and compile to Java 8 with the `--release` option. This option is currently incompatible with our build setup.
Go to "Settings" → "Build, Execution, Deployment" → "Compiler" → "Java Compiler" and uncheck the "Use '--release' option for cross-compilation (Java 9 and later)".

#### Examples fail with a `NoClassDefFoundError` for Fluss classes.

This happens if Fluss dependencies are set to "provided", resulting in them not being available
on the classpath. You can either check "Include dependencies with 'Provided' scope" in your
run configuration, or create a test that calls the `main()` method of the example.