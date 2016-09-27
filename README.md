# Try artifact

TryArtifact is a tool for testing a maven artifacts in the JShell.

## Usage

Download from https://github.com/kawasima/try-artifact/releases/tag/v0.2.0

Start TryArtifact's JShell.

```
% java -jar try-artifact-0.2.0.jar

|  Welcome to JShell -- Version (version info not available)
|  Type /help for help

-> 
```

The `/resolve` command is available in the TryArtifact's JShell.

```
-> /help
|  Type a Java language expression, statement, or declaration.
|  Or type one of the following commands:
|  
|     /list [all|start|<name or id>]                             -- list the source you have typed
|     /edit <name or id>                                         -- edit a source entry referenced by name or id
|     /drop <name or id>                                         -- delete a source entry referenced by name or id
|     /save [all|history|start] <file>                           -- Save snippet source to a file.
|     /open <file>                                               -- open a file as source input
|     /vars                                                      -- list the declared variables and their values
|     /methods                                                   -- list the declared methods and their signatures
|     /classes                                                   -- list the declared classes
|     /imports                                                   -- list the imported items
|     /exit                                                      -- exit jshell
|     /reset                                                     -- reset jshell
|     /reload [restore] [quiet]                                  -- reset and replay relevant history -- current or previous (restore)
|     /classpath <path>                                          -- add a path to the classpath
|     /history                                                   -- history of what you have typed
|     /help [<command>|<subject>]                                -- get information about jshell
|     /set editor|start|feedback|newmode|prompt|format|field ... -- set jshell configuration information
|     /?                                                         -- get information about jshell
|     /!                                                         -- re-run last snippet
|     /<id>                                                      -- re-run snippet by id
|     /-<n>                                                      -- re-run n-th previous snippet
|     /resolve <spec>                                            -- resolve an artifact
|  
|  For more information type '/help' followed by the name of command or a subject.
|  For example '/help /list' or '/help intro'.  Subjects:
|  
|     intro     -- An introduction to the jshell tool
|     shortcuts -- Describe shortcuts

```

Execute the `/resolve` command with a artifact that you'll try.
When you press `TAB` key, the candidates of artifacts are shown.

```
-> /resolve org.
org.carewebframework:org.carewebframework-parent:pom:4.0.6               org.carewebframework:org.carewebframework.amqp-parent:pom:4.0.6          org.carewebframework:org.carewebframework.api-parent:pom:4.0.6           
org.carewebframework:org.carewebframework.ext-parent:pom:4.0.6           org.carewebframework:org.carewebframework.help-parent:pom:4.0.6          org.carewebframework:org.carewebframework.jms-parent:pom:4.0.6           
org.carewebframework:org.carewebframework.lib-parent:pom:4.0.6           org.carewebframework:org.carewebframework.lib.plugin:pom:4.0.6           org.carewebframework:org.carewebframework.mvn-parent:pom:4.0.6           
org.carewebframework:org.carewebframework.ui-parent:pom:4.0.6            org.jibx.schema.org.apache.maven:org.apache.maven:pom:1.1.4              org.jibx.schema.org.docbook:org.docbook:pom:1.1.4                        
org.jibx.schema.org.hr_xml:org.hr_xml:pom:1.0.6                          org.jibx.schema.org.w3:org.w3:pom:1.1.4                                  org.jibx.schema.org.xmlsoap.schemas:org.xmlsoap.schemas:pom:1.1.4        
org.jresearch:org.jresearch.pom:pom:25                                   org.openwms:org.openwms:pom:1.0                                          org.shaneking:org.shaneking:pom:0.0.1                                    
org.smartdeveloperhub.harvester.org:org-harvester-aggregator:pom:0.1.0   org.smartdeveloperhub.harvester.org:org-harvester-container:pom:0.1.0    

```

When the artifact is resolved, you can be using it.

```
-> /resolve org.apache.commons:commons-lang3:jar:3.4
|  Path /home/kawasima/.m2/repository/org/apache/commons/commons-lang3/3.4/commons-lang3-3.4.jar added to classpath


-> org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric(30)
|  Expression value is: "8D1ysKPGhHPLGpsducw0ch0SnSfixb"
|    assigned to temporary variable $1 of type String
```
