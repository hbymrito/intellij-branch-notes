# Git Branch Notes

An IntelliJ IDEA plugin that displays Git branch notes in this form:

```text
feature/user-profile [Add user profile page]
fix/login-timeout [Fix login timeout handling]
```

Local branch notes use Git's native branch description storage:

```bash
git config branch.feature/user-profile.description "Add user profile page"
```

Remote branch notes use a separate plugin-owned key in the same local Git configuration:

```bash
git config branchnotes.remote.origin/feature/user-profile.description "Remote review branch"
```

IDEA's Git Log branch dashboard shows local and remote branch notes. Native single-branch action menus provide **Add Remark**, **Edit Remark**, and **Delete Remark** actions.

When a new local tracking branch is created from a remote branch that has a note, the note is copied to the new local branch once. When IntelliJ IDEA Push creates a new remote branch, the local branch note is copied to it once; command-line pushes are also detected when they establish an upstream relationship. Existing target notes are never overwritten, and later edits remain independent.

The optional status-bar widget shows only the current local branch and its note. It is disabled by default and can be enabled or hidden from IDEA's status-bar context menu.

## Usage

1. Open the Git Log branch dashboard.
2. Open the action menu for a local or remote branch.
3. Choose **Add Remark** or **Edit Remark**.
4. Enter a note. The Git Log branch dashboard refreshes automatically.

All notes stay in the local repository's Git configuration and are not pushed to remotes.

## Development

Requirements:

- IntelliJ IDEA 2026.2 at `~/Applications/IntelliJ IDEA.app`, or access to download the 2026.2 platform
- JDK 25; the JBR bundled with IDEA 2026.2 is sufficient

```bash
JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  ./gradlew test buildPlugin verifyPlugin
```

The plugin ZIP is created under `build/distributions/`.

## License

This project is licensed under the [MIT License](LICENSE).
