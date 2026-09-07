#!/usr/bin/env bash
# =============================================================================
# api-tour.sh - exercises every endpoint and every business rule, and checks
#               that each returns what it should.
#
#   ./scripts/api-tour.sh
#
# Run it against a FRESH database, because several checks depend on the seeded
# state (nobody enrolled yet, MA101 at its original capacity):
#
#   docker compose down -v && docker compose up -d
#
# Why a script rather than a list of curl commands in the README: a script can
# be RUN. Documentation drifts from reality the moment someone changes an
# endpoint; an executable check fails loudly instead.
# =============================================================================
set -u

BASE="${BASE:-http://localhost:8280/enrollment/api}"
pass=0
fail=0

# ---- tiny assertion helpers -------------------------------------------------
check() {  # check <name> <expected> <actual>
    if [ "$2" = "$3" ]; then
        printf '  \033[32mPASS\033[0m  %-38s %s\n' "$1" "$3"
        pass=$((pass + 1))
    else
        printf '  \033[31mFAIL\033[0m  %-38s expected %s, got %s\n' "$1" "$2" "$3"
        fail=$((fail + 1))
    fi
}

# Sends a request; prints the status code; leaves the body in /tmp/tour.json
st() { curl -s -o /tmp/tour.json -w '%{http_code}' "$@"; }

# Extracts the errorCode from the last response body
ec() { grep -o '"errorCode":"[^"]*"' /tmp/tour.json | cut -d'"' -f4; }

# Sends a request and prints its errorCode instead of its status
code_of() { st "$@" > /dev/null; ec; }

json() { echo -H 'Content-Type: application/json'; }

# ---- resolve the seeded ids -------------------------------------------------
sid() { curl -s "$BASE/students/by-number/$1" | grep -o '"id":[0-9]*' | cut -d: -f2; }

LUCA=$(sid 100001)
SOFIA=$(sid 100002)
MATTEO=$(sid 100003)
CHIARA=$(sid 100004)

if [ -z "$LUCA" ]; then
    echo "Cannot reach $BASE - is the stack running? (docker compose up -d)"
    exit 1
fi

COURSES=$(curl -s "$BASE/courses?year=2025&size=10")
gid() { printf '%s' "$COURSES" | tr '}' '\n' | grep "\"code\":\"$1\"" \
        | grep -o '"id":[0-9]*' | cut -d: -f2; }

CS101=$(gid CS101); CS301=$(gid CS301); CS401=$(gid CS401); MA101=$(gid MA101)
CS150=$(curl -s "$BASE/courses?year=2024" | grep -o '"id":[0-9]*' | cut -d: -f2)

echo
echo "students  luca=$LUCA sofia=$SOFIA matteo=$MATTEO chiara=$CHIARA(suspended)"
echo "courses   CS101=$CS101 CS301=$CS301 CS401=$CS401 MA101=$MA101 CS150=$CS150(closed)"

# =============================================================================
echo; echo "READS"
# =============================================================================
check "GET /courses/open"           200 "$(st "$BASE/courses/open")"
check "GET /courses/{id}"           200 "$(st "$BASE/courses/$CS401")"
check "  detail has prerequisites"  1   "$(grep -c '"prerequisiteCodes"' /tmp/tour.json)"
check "  detail has professor"      1   "$(grep -c '"professorName"' /tmp/tour.json)"
check "GET /professors"             200 "$(st "$BASE/professors")"
check "GET /students paginated"     200 "$(st "$BASE/students?page=0&size=2")"
check "  page metadata present"     1   "$(grep -c '"totalElements"' /tmp/tour.json)"
check "GET /students/{id}"          200 "$(st "$BASE/students/$LUCA")"
check "GET /students/by-number"     200 "$(st "$BASE/students/by-number/100001")"
check "GET unknown student"         404 "$(st "$BASE/students/999999")"
check "GET unknown course"          404 "$(st "$BASE/courses/999999")"

# =============================================================================
echo; echo "ENROLLMENT RULES"
# =============================================================================
check "enrol Luca in CS101"   201 \
    "$(st -X POST "$BASE/enrollments" -H 'Content-Type: application/json' \
        -d "{\"studentId\":$LUCA,\"courseId\":$CS101}")"
ENR=$(grep -o '"id":[0-9]*' /tmp/tour.json | head -1 | cut -d: -f2)

check "duplicate enrollment"  DUPLICATE_RESOURCE \
    "$(code_of -X POST "$BASE/enrollments" -H 'Content-Type: application/json' \
        -d "{\"studentId\":$LUCA,\"courseId\":$CS101}")"

check "suspended student"     STUDENT_NOT_ELIGIBLE \
    "$(code_of -X POST "$BASE/enrollments" -H 'Content-Type: application/json' \
        -d "{\"studentId\":$CHIARA,\"courseId\":$CS101}")"

check "closed window"         ENROLLMENT_WINDOW_CLOSED \
    "$(code_of -X POST "$BASE/enrollments" -H 'Content-Type: application/json' \
        -d "{\"studentId\":$LUCA,\"courseId\":$CS150}")"

check "prerequisites unmet"   PREREQUISITES_NOT_MET \
    "$(code_of -X POST "$BASE/enrollments" -H 'Content-Type: application/json' \
        -d "{\"studentId\":$SOFIA,\"courseId\":$CS401}")"

# =============================================================================
echo; echo "CAPACITY"
# =============================================================================
check "shrink MA101 to 2 seats" 200 "$(st -X PATCH "$BASE/courses/$MA101/capacity?value=2")"
check "seat 1 of 2"             201 \
    "$(st -X POST "$BASE/enrollments" -H 'Content-Type: application/json' \
        -d "{\"studentId\":$SOFIA,\"courseId\":$MA101}")"
check "seat 2 of 2"             201 \
    "$(st -X POST "$BASE/enrollments" -H 'Content-Type: application/json' \
        -d "{\"studentId\":$MATTEO,\"courseId\":$MA101}")"
check "seat 3 refused"          COURSE_FULL \
    "$(code_of -X POST "$BASE/enrollments" -H 'Content-Type: application/json' \
        -d "{\"studentId\":$LUCA,\"courseId\":$MA101}")"
check "shrink below enrolled"   CAPACITY_BELOW_ENROLLED \
    "$(code_of -X PATCH "$BASE/courses/$MA101/capacity?value=1")"
check "capacity 0 is bad input" 400 "$(st -X PATCH "$BASE/courses/$MA101/capacity?value=0")"

# =============================================================================
echo; echo "GRADING AND THE STATE MACHINE"
# =============================================================================
check "lode with a 28"        INVALID_GRADE \
    "$(code_of -X POST "$BASE/enrollments/$ENR/grade" -H 'Content-Type: application/json' \
        -d '{"grade":28,"withHonours":true}')"

check "record 30 e lode"      200 \
    "$(st -X POST "$BASE/enrollments/$ENR/grade" -H 'Content-Type: application/json' \
        -d '{"grade":30,"withHonours":true}')"
check "  formatted grade"     '"30 e lode"' \
    "$(grep -o '"formattedGrade":"[^"]*"' /tmp/tour.json | cut -d: -f2)"
check "  status COMPLETED"    '"COMPLETED"' \
    "$(grep -o '"status":"[^"]*"' /tmp/tour.json | cut -d: -f2)"

check "grading twice refused" ILLEGAL_STATE_TRANSITION \
    "$(code_of -X POST "$BASE/enrollments/$ENR/grade" -H 'Content-Type: application/json' \
        -d '{"grade":30,"withHonours":true}')"

# CS301 requires CS101, which Luca has now passed.
check "prerequisite satisfied" 201 \
    "$(st -X POST "$BASE/enrollments" -H 'Content-Type: application/json' \
        -d "{\"studentId\":$LUCA,\"courseId\":$CS301}")"
ENR2=$(grep -o '"id":[0-9]*' /tmp/tour.json | head -1 | cut -d: -f2)

check "withdraw"              200 "$(st -X DELETE "$BASE/enrollments/$ENR2")"
check "  status WITHDRAWN"    '"WITHDRAWN"' \
    "$(grep -o '"status":"[^"]*"' /tmp/tour.json | cut -d: -f2)"
check "withdraw twice refused" ILLEGAL_STATE_TRANSITION \
    "$(code_of -X DELETE "$BASE/enrollments/$ENR2")"

# =============================================================================
echo; echo "TRANSCRIPT"
# =============================================================================
st "$BASE/students/$LUCA" > /dev/null
check "earned credits"        '12' "$(grep -o '"earnedCredits":[0-9]*' /tmp/tour.json | cut -d: -f2)"
check "weighted average"      '30.0' "$(grep -o '"weightedAverage":[0-9.]*' /tmp/tour.json | cut -d: -f2)"
# The list endpoint must NOT carry these - it does not load the transcript.
st "$BASE/students?size=5" > /dev/null
check "omitted from list view" 0 "$(grep -c '"earnedCredits"' /tmp/tour.json)"

# =============================================================================
echo; echo "VALIDATION AND ERROR CONTRACT"
# =============================================================================
check "malformed body -> 400"  400 \
    "$(st -X POST "$BASE/students" -H 'Content-Type: application/json' \
        -d '{"studentNumber":"12AB","firstName":"","email":"x","enrollmentYear":1800}')"
check "  every bad field named" 6 "$(grep -o '"field"' /tmp/tour.json | tr -d ' ' | wc -l | tr -d ' ')"
check "unknown enum -> 400"    400 "$(st "$BASE/students?status=BANANA")"
check "  errorCode"            INVALID_PARAMETER "$(ec)"
check "RFC 7807 type present"  1 "$(grep -c '"type":"https://api.unicam.it/problems/' /tmp/tour.json)"
check "correlation id in body" 1 "$(grep -c '"correlationId"' /tmp/tour.json)"

CID=$(curl -s -D - -o /dev/null "$BASE/professors" \
      | grep -i '^x-correlation-id' | tr -d '\r' | cut -d' ' -f2)
check "X-Correlation-Id header" 8 "${#CID}"

# =============================================================================
echo
if [ "$fail" -eq 0 ]; then
    printf '\033[32m================  %d passed, %d failed  ================\033[0m\n' "$pass" "$fail"
else
    printf '\033[31m================  %d passed, %d failed  ================\033[0m\n' "$pass" "$fail"
fi
echo
echo "Tip: every request above was logged with a correlation id. Try:"
echo "  docker compose logs app | grep $CID"
echo

[ "$fail" -eq 0 ]
