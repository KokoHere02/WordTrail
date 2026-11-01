#!/usr/bin/env bash
set -Eeuo pipefail

# Run with Bash and Git; creates only temporary local repositories and never
# touches the real deployment or a database.
SOURCE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
TEMP_ROOT=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
WORK=$(mktemp -d "$TEMP_ROOT/wordtrail-update-test.XXXXXX")
case "$WORK" in "$TEMP_ROOT"/wordtrail-update-test.*) ;; *) echo 'Unsafe temporary path' >&2; exit 1 ;; esac
trap 'rm -rf -- "$WORK"' EXIT

for name in backend frontend; do
  git init --quiet --bare --initial-branch=main "$WORK/$name.git"
  git clone --quiet "$WORK/$name.git" "$WORK/$name-seed" 2>/dev/null
  git -C "$WORK/$name-seed" config user.name 'Update test'
  git -C "$WORK/$name-seed" config user.email 'update-test@example.invalid'
  printf 'initial\n' > "$WORK/$name-seed/tracked.txt"
  git -C "$WORK/$name-seed" add tracked.txt
  git -C "$WORK/$name-seed" commit --quiet -m initial
  git -C "$WORK/$name-seed" push --quiet origin main
done
git clone --quiet "$WORK/backend.git" "$WORK/WordTrail"
git clone --quiet "$WORK/frontend.git" "$WORK/wordtrail-web"
mkdir -p "$WORK/WordTrail/deploy"
cp "$SOURCE_DIR/update.sh" "$WORK/WordTrail/deploy/update.sh"
touch "$WORK/WordTrail/deploy/.env"
cat > "$WORK/WordTrail/deploy/deploy.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ -d "$WORDTRAIL_WEB_DIR" ]]
printf 'deployed\n' >> "$WORDTRAIL_WEB_DIR/deploy-test-marker"
EOF

for name in backend frontend; do
  printf 'remote update\n' >> "$WORK/$name-seed/tracked.txt"
  git -C "$WORK/$name-seed" add tracked.txt
  git -C "$WORK/$name-seed" commit --quiet -m update
  git -C "$WORK/$name-seed" push --quiet origin main
done
bash "$WORK/WordTrail/deploy/update.sh" >/dev/null
for pair in 'backend WordTrail' 'frontend wordtrail-web'; do
  read -r seed checkout <<< "$pair"
  [[ $(git -C "$WORK/$seed-seed" rev-parse HEAD) == "$(git -C "$WORK/$checkout" rev-parse HEAD)" ]]
done
[[ $(wc -l < "$WORK/wordtrail-web/deploy-test-marker") -eq 1 ]]
echo 'PASS: both repositories update and the existing deploy script runs'

# A dirty frontend must stop the script before it updates the backend.
printf 'pending remote backend update\n' >> "$WORK/backend-seed/tracked.txt"
git -C "$WORK/backend-seed" add tracked.txt
git -C "$WORK/backend-seed" commit --quiet -m another-update
git -C "$WORK/backend-seed" push --quiet origin main
before=$(git -C "$WORK/WordTrail" rev-parse HEAD)
printf 'local changes\n' >> "$WORK/wordtrail-web/tracked.txt"
if bash "$WORK/WordTrail/deploy/update.sh" >/dev/null 2>&1; then echo 'Dirty frontend should fail' >&2; exit 1; fi
[[ $(git -C "$WORK/WordTrail" rev-parse HEAD) == "$before" ]]
[[ $(tail -n 1 "$WORK/wordtrail-web/tracked.txt") == 'local changes' ]]
[[ $(wc -l < "$WORK/wordtrail-web/deploy-test-marker") -eq 1 ]]
echo 'PASS: tracked local changes are preserved and stop both pulls'

git -C "$WORK/wordtrail-web" restore tracked.txt
rm -- "$WORK/WordTrail/deploy/.env"
if bash "$WORK/WordTrail/deploy/update.sh" >/dev/null 2>&1; then echo 'Missing configuration should fail' >&2; exit 1; fi
[[ $(git -C "$WORK/WordTrail" rev-parse HEAD) == "$before" ]]
echo 'PASS: missing deployment configuration stops before pulling'

touch "$WORK/WordTrail/deploy/.env"
git -C "$WORK/WordTrail" config user.name 'Update test'
git -C "$WORK/WordTrail" config user.email 'update-test@example.invalid'
printf 'local commit\n' >> "$WORK/WordTrail/tracked.txt"
git -C "$WORK/WordTrail" add tracked.txt
git -C "$WORK/WordTrail" commit --quiet -m local-commit
diverged=$(git -C "$WORK/WordTrail" rev-parse HEAD)
if bash "$WORK/WordTrail/deploy/update.sh" >/dev/null 2>&1; then echo 'Diverged history should fail' >&2; exit 1; fi
[[ $(git -C "$WORK/WordTrail" rev-parse HEAD) == "$diverged" ]]
[[ $(wc -l < "$WORK/wordtrail-web/deploy-test-marker") -eq 1 ]]
echo 'PASS: divergent history is not merged or deployed'
