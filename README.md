# Git Branch Notes

An IntelliJ IDEA plugin that displays Git branch notes in this form:

```text
feature/user-profile [Add user profile page]
fix/login-timeout [Fix login timeout handling]
```

Notes use Git's native local branch description storage:

```bash
git config branch.feature/user-profile.description "Add user profile page"
```

IDEA's Git Log branch dashboard shows each local branch followed by its note. The separate status-bar widget shows the current branch and note; click it to edit or clear notes, or to check out a branch.

The secondary Git Branches popup is intentionally not modified. This keeps the plugin out of IDEA's global AWT event path and avoids adding work to unrelated menus, buttons, and dialogs.

## Development

Requirements:

- IntelliJ IDEA 2026.2 at `~/Applications/IntelliJ IDEA.app`, or access to download the 2026.2 platform
- JDK 25; the JBR bundled with IDEA 2026.2 is sufficient

```bash
JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  ./gradlew test buildPlugin verifyPlugin
```

The plugin ZIP is created under `build/distributions/`.
