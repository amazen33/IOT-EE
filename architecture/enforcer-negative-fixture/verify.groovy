// Runs after the fixture build above, regardless of its outcome
// (maven-invoker-plugin convention). invoker.properties already
// asserts the build failed; this script additionally proves it failed
// for the ENFORCER RULE's own stated reason, not for an unrelated
// error (e.g. a missing systemPath file, a malformed pom) that would
// also produce invoker.buildResult=failure without the rule itself
// having fired at all. A thrown exception here fails this fixture's
// invoker verification, the same as a wrong buildResult would.
File logFile = new File(basedir, "build.log")
assert logFile.exists() : "expected maven-invoker-plugin to have written build.log next to this fixture"

String log = logFile.text
assert log.contains("ADR 0013") :
        "expected the Enforcer rule's own custom <message> text (naming ADR 0013) in the build log; " +
        "if it is absent, the build failed for some other reason and the enforce-no-shared-runtime " +
        "rule was never actually exercised -- got:\n" + log

assert log.contains("enforce-no-shared-runtime") :
        "expected the failing execution id enforce-no-shared-runtime to be named in the build log -- got:\n" + log

return true
