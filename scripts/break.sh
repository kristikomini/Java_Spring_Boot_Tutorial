#!/usr/bin/env bash
# =============================================================================
# break.sh - introduce one classic Jakarta EE bug on purpose, then undo it
# =============================================================================
# Reading about the N+1 problem teaches you the words. Watching your own server
# fire fifty queries for one request teaches you the thing.
#
# Each break below is a single-line edit to a tracked file. `restore` puts every
# one of them back with `git checkout`, so nothing is ever lost - but for that to
# be safe the target file must be clean before we touch it, which is checked.
#
#   ./scripts/break.sh list
#   ./scripts/break.sh fetch-plan
#   ./scripts/break.sh restore
#
# After breaking, rebuild and watch:
#   mvn package                 # WildFly hot-redeploys in a second or two
#   tail -f <wildfly>/standalone/log/server.log
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

COURSE_REPO="src/main/java/it/unicam/cs/enrollment/repository/CourseRepository.java"
ENROLL_SVC="src/main/java/it/unicam/cs/enrollment/service/EnrollmentService.java"
COURSE_SVC="src/main/java/it/unicam/cs/enrollment/service/CourseService.java"
COURSE_IT="src/test/java/it/unicam/cs/enrollment/repository/CourseRepositoryIT.java"

ALL_FILES=("$COURSE_REPO" "$COURSE_SVC" "$ENROLL_SVC" "$COURSE_IT")

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
dim()   { printf '\033[2m%s\033[0m\n' "$*"; }

require_clean() {
  local file="$1"
  if ! git diff --quiet -- "$file"; then
    red "Refusing to edit $file - it already has uncommitted changes."
    echo "Run './scripts/break.sh restore' first, or commit your work."
    exit 1
  fi
}

usage() {
  bold "break.sh - deliberately introduce a classic bug"
  echo
  echo "  fetch-plan      LazyInitializationException on GET /courses/{id}"
  echo "  n-plus-one      one query becomes one-per-row on GET /courses"
  echo "  no-lock         removes the row lock that prevents a seat race"
  echo "  blind-test      makes a real bug invisible to the test suite"
  echo
  echo "  restore         put everything back"
  echo "  status          show what is currently broken"
  echo
  dim "After any break:  mvn package   (WildFly redeploys automatically)"
}

case "${1:-}" in

# -----------------------------------------------------------------------------
fetch-plan)
  require_clean "$COURSE_REPO"
  sed -i '/+ "LEFT JOIN FETCH c.prerequisites "/d' "$COURSE_REPO"
  red "BROKEN: removed the prerequisites fetch join."
  echo
  bold "What to do"
  echo "  mvn package"
  echo "  curl -i http://localhost:8280/enrollment/api/courses/55"
  echo
  bold "What you should see"
  echo "  500, with a clean RFC 7807 body carrying a correlation id."
  echo "  In server.log: LazyInitializationException, thrown in CourseMapper."
  echo
  bold "Why"
  echo "  The mapper reads course.getPrerequisites() AFTER the transaction has"
  echo "  committed, so the entity is detached and there is no session left to"
  echo "  run the lazy query. A fetch plan is a contract between a query and"
  echo "  every consumer of its result."
  echo
  dim "  Bonus: run 'mvn verify' - CourseRepositoryIT catches it, because it"
  dim "  calls entityManager.clear() first. Then try 'break.sh blind-test'."
  ;;

# -----------------------------------------------------------------------------
n-plus-one)
  require_clean "$COURSE_SVC"
  sed -i 's|        return enrollmentRepository.countOccupiedSeatsByCourse(courseIds);|        return courseIds.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> enrollmentRepository.countOccupiedSeats(id)));|' "$COURSE_SVC"
  red "BROKEN: seat counts are now fetched one course at a time."
  echo
  bold "What to do"
  echo "  mvn package"
  echo "  # then count the SQL statements one list request produces:"
  echo "  curl -s 'http://localhost:8280/enrollment/api/courses?year=2025&size=10' >/dev/null"
  echo "  grep -c 'DEBUG \[org.hibernate.SQL\]' <wildfly>/standalone/log/server.log"
  echo
  bold "What you should see"
  echo "  3 statements before the break, 7 after - with five courses. The extra"
  echo "  four are identical except for the id. Seed five hundred courses and it"
  echo "  is five hundred round trips for one page of results."
  echo
  bold "Why"
  echo "  countOccupiedSeatsByCourse does the whole job in ONE query with a"
  echo "  'WHERE course_id IN (...) GROUP BY course_id'. The broken version"
  echo "  loops instead. Nothing errors. Nothing is slow with test data. It only"
  echo "  hurts in production, which is what makes N+1 so easy to ship."
  echo
  dim "  This is the version of N+1 that stays silent. Contrast it with"
  dim "  'fetch-plan', where the same class of mistake fails loudly instead."
  ;;

# -----------------------------------------------------------------------------
no-lock)
  require_clean "$ENROLL_SVC"
  sed -i 's|courseRepository.findByIdWithPessimisticLock(courseId)|courseRepository.findById(courseId)|' "$ENROLL_SVC"
  red "BROKEN: the course is no longer locked while seats are counted."
  echo
  bold "What to do"
  echo "  mvn package"
  echo "  curl -s -X POST http://localhost:8280/enrollment/api/enrollments \\"
  echo "       -H 'Content-Type: application/json' \\"
  echo "       -d '{\"studentId\":102,\"courseId\":53}' > /dev/null"
  echo "  grep -c 'FOR NO KEY UPDATE' <wildfly>/standalone/log/server.log"
  echo
  bold "What you should see"
  echo "  The count stops increasing: that clause is gone from the SQL."
  echo
  bold "Why this one is different"
  echo "  Every test still passes and every request still works. The bug only"
  echo "  appears when two requests interleave: both read 'one seat left', both"
  echo "  decide it is fine, both insert. Race conditions do not show up in a"
  echo "  single-threaded test - which is exactly why you reason about them"
  echo "  from the code rather than waiting for a failure."
  ;;

# -----------------------------------------------------------------------------
blind-test)
  require_clean "$COURSE_IT"
  sed -i '122s|entityManager.clear();|// entityManager.clear();  // disabled by break.sh|' "$COURSE_IT"
  red "BROKEN: the integration test no longer detaches before asserting."
  echo
  bold "What to do"
  echo "  ./scripts/break.sh fetch-plan     # introduce a REAL bug as well"
  echo "  mvn verify                        # ... and watch it pass"
  echo "  curl -i http://localhost:8280/enrollment/api/courses/55   # still 500"
  echo
  bold "What you should see"
  echo "  BUILD SUCCESS while the endpoint returns 500 to real users."
  echo
  bold "Why"
  echo "  With the persistence context still open, a lazy getter silently fires"
  echo "  another query and succeeds. The test was passing for a reason that"
  echo "  does not exist in production, where the mapper runs after commit."
  echo
  bold "The lesson"
  echo "  A test configuration that differs from production will happily prove"
  echo "  the wrong thing. The value of a test is not that it passes - it is"
  echo "  that it would have failed."
  ;;

# -----------------------------------------------------------------------------
restore)
  changed=0
  for f in "${ALL_FILES[@]}"; do
    if ! git diff --quiet -- "$f"; then
      git checkout -- "$f"
      echo "  restored $f"
      changed=1
    fi
  done
  if [ "$changed" -eq 0 ]; then
    echo "Nothing was broken."
  else
    green "All restored. Run 'mvn verify' to confirm 60/60, then 'mvn package'."
  fi
  ;;

# -----------------------------------------------------------------------------
status)
  broken=0
  for f in "${ALL_FILES[@]}"; do
    if ! git diff --quiet -- "$f"; then
      red "  modified: $f"
      broken=1
    fi
  done
  [ "$broken" -eq 0 ] && green "Nothing is broken." || echo "  (./scripts/break.sh restore)"
  ;;

# -----------------------------------------------------------------------------
list|"")
  usage
  ;;

*)
  red "Unknown break: $1"
  echo
  usage
  exit 1
  ;;
esac
