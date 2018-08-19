// https://mvnrepository.com/artifact/com.tdunning/t-digest

lazy val core = (project in file("core"))
  .settings(libraryDependencies += "com.tdunning" % "t-digest" % "3.2")

lazy val bench = (project in file("bench"))
  .dependsOn(core)
  .enablePlugins(JmhPlugin)
  .settings(
    classDirectory in Jmh := (classDirectory in Test).value,
    compile in Jmh := (compile in Jmh).dependsOn(compile in Test).value,
    dependencyClasspath in Jmh := (dependencyClasspath in Test).value,
    run in Jmh := (run in Jmh).dependsOn(Keys.compile in Jmh).evaluated,
    sourceDirectory in Jmh := (sourceDirectory in Test).value
  )

scalaVersion in Global := "2.12.2"
javacOptions in Global ++= Seq("-source", "1.8", "-target", "1.8", "-encoding", "UTF-8")
scalacOptions in Global ++= Seq(
  s"-target:jvm-1.8",
  "-encoding", "utf-8",                // Specify character encoding used by source files.
  "-explaintypes",                     // Explain type errors in more detail.
  "-feature",                          // Emit warning and location for usages of features that should be imported explicitly.
  "-unchecked",                        // Enable additional warnings where generated code depends on assumptions.
  "-Xlint:adapted-args",               // Warn if an argument list is modified to match the receiver.
  "-Xlint:nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
  "-Xlint:nullary-unit",               // Warn when nullary methods return Unit.
  "-Xlint:private-shadow",             // A private field (or class parameter) shadows a superclass field.
  "-Ypatmat-exhaust-depth", "off",     // Allow unbounded pattern match exhaustiveness checking
  "-Ywarn-unused:imports",             // Warn if an import selector is not referenced.
  "-Ywarn-unused:locals"               // Warn if a local definition is unused.
)
