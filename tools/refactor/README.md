# Refactoring checks

`MethodLengths.java` lists the methods of the production code (everything under `src/main/java/dev/starfold`
except `dev/` and `mixin/`) that are 120 lines or longer, measured with javac's parser from declaration to
closing brace:

```
java tools/refactor/MethodLengths.java
```

Two methods are longer on purpose, because splitting them further would allocate on every tick:
`space/SpaceServer.advanceLead` and `ship/Airstair.tickServerStair`.
