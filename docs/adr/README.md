# ADR numbering convention

ADR numbers are assigned when the ADR merges to `main`, not when a
branch is created. Drafts live at `docs/adr/XXXX-proposed-<slug>.md`
using a placeholder number; the final number is assigned and the file
renamed at merge time. If two in-flight branches both draft an ADR, the
one that merges first takes the next free number; the other renumbers
at its own merge. This avoids the parallel-branch collision that
produced ADR 0011 (renumbered from a requested 0009, because 0009 and
0010 were already taken by an already-merged ADR and a still-unmerged
sibling branch respectively).
