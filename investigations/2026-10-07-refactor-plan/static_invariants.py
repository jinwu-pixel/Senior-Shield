#!/usr/bin/env python3
"""R1 lexical inventory; python -I static_invariants.py Coordinator.kt.

Counts cover the whole file, not only start(). Comments, declarations and plain
string text are excluded from code counts. Kotlin string-template expressions
(including $name reads) ARE code. Literal multisets retain source spelling,
quotes, escapes and interpolation verbatim; no Unicode/newline normalization.
This is an inventory, not a proof of ordering, short-circuiting or equivalence.
"""

import collections
import json
import re
import sys
from pathlib import Path


IDENT = re.compile(r"[A-Za-z_][A-Za-z_0-9]*")
HOOKS = (
    "afterDebugEpochCapturedBeforePublish",
    "afterDebugPublicationBeforeEffects",
    "beforeDebugOverlayEffect",
    "beforeInactiveSessionCleanupCommit",
    "beforeRenewalDowngradeCleanup",
    "beforePublicationNotificationCommit",
    "beforePublicationPopupAccountingCommit",
)
VOLATILES = (
    "previousBankingForeground", "cooldownConsumedSessionId", "s2RecRefireState",
)
K5_CALLS = (
    "cooldownManager.isShowing", "sessionTracker.isSnoozeActive",
    "sessionTracker.isSnoozedForCall", "sessionTracker.snoozedCallIdOrNull",
    "sessionTracker.snoozedAtOrNull", "overlayManager.isEndCallSuppressed",
    "callMonitor.currentCallId", "clock",
    # Additional state-query surfaces in the current Coordinator (K5 is non-exhaustive).
    "sessionTracker.isCurrentSessionIdleTimedOut",
    "callMonitor.isTelebankingAnchorHot",
    "appUsageMonitor.latestBankingForegroundEventTimestamp",
)
K5_PROPERTIES = (
    "sessionTracker.sessionState.value", "sessionTracker.userResetEpoch",
    "cooldownManager.showedAtMillis", "cooldownManager.dismissedAtMillis",
    "cooldownManager.lastCountdownSec",
)


class KotlinLex:
    """Small scanner supporting nested comments and nested string templates."""

    def __init__(self, source):
        self.source = source
        self.tokens = []  # (text, byte-decoded character offset)
        self.strings = []  # (start, end, exact source spelling)

    def scan(self, pos=0, template=False):
        s = self.source
        braces = 0
        while pos < len(s):
            if s[pos].isspace():
                pos += 1
            elif s.startswith("//", pos):
                end = s.find("\n", pos)
                pos = len(s) if end < 0 else end
            elif s.startswith("/*", pos):
                depth = 1
                pos += 2
                while depth and pos < len(s):
                    if s.startswith("/*", pos):
                        depth += 1
                        pos += 2
                    elif s.startswith("*/", pos):
                        depth -= 1
                        pos += 2
                    else:
                        pos += 1
                if depth:
                    raise ValueError("unterminated block comment")
            elif s[pos] == '"':
                pos = self.string(pos)
            elif s[pos] == "'":
                start = pos
                pos += 1
                while pos < len(s) and s[pos] != "'":
                    pos += 2 if s[pos] == "\\" else 1
                if pos >= len(s):
                    raise ValueError("unterminated character literal")
                pos += 1
                self.tokens.append((s[start:pos], start))
            elif template and s[pos] == "}" and braces == 0:
                return pos + 1
            else:
                match = IDENT.match(s, pos)
                if match:
                    value = match.group()
                else:
                    value = next((op for op in ("==", "!=", "<=", ">=", "+=", "-=", "++", "--", "->")
                                  if s.startswith(op, pos)), s[pos])
                if value == "{":
                    braces += 1
                elif value == "}":
                    braces -= 1
                self.tokens.append((value, pos))
                pos += len(value)
        if template:
            raise ValueError("unterminated string template")
        return pos

    def string(self, start):
        s = self.source
        quote = '"""' if s.startswith('"""', start) else '"'
        pos = start + len(quote)
        # A boundary token prevents tokens either side of a literal being joined.
        self.tokens.append(("<string>", start))
        while pos < len(s):
            if s.startswith(quote, pos):
                end = pos + len(quote)
                self.strings.append((start, end, s[start:end]))
                self.tokens.append(("</string>", end - 1))
                return end
            if quote == '"' and s[pos] == "\\":
                pos += 2
            elif s.startswith("${", pos):
                self.tokens.append(("<template>", pos))
                pos = self.scan(pos + 2, template=True)
                self.tokens.append(("</template>", pos - 1))
            elif s[pos] == "$" and IDENT.match(s, pos + 1):
                match = IDENT.match(s, pos + 1)
                self.tokens.extend((("<template>", pos), (match.group(), pos + 1),
                                    ("</template>", match.end())))
                pos = match.end()
            else:
                pos += 1
        raise ValueError("unterminated string literal")


def inventory(source):
    lexer = KotlinLex(source)
    lexer.scan()
    tokens = sorted(lexer.tokens, key=lambda token: token[1])
    words = [token[0] for token in tokens]

    def indices(pattern):
        return [i for i in range(len(words) - len(pattern) + 1)
                if words[i:i + len(pattern)] == pattern]

    def name_tokens(name):
        return re.findall(r"[A-Za-z_][A-Za-z_0-9]*|\.", name)

    def calls(name):
        return [i for i in indices(name_tokens(name) + ["("])
                if i == 0 or words[i - 1] not in ("fun", "@")]

    def call_end(open_index):
        depth = 0
        for i in range(open_index, len(words)):
            if words[i] == "(":
                depth += 1
            elif words[i] == ")":
                depth -= 1
                if depth == 0:
                    return tokens[i][1]
        raise ValueError("unbalanced call parentheses")

    def strings_in_call(start, open_index):
        end = call_end(open_index)
        return [raw for left, right, raw in lexer.strings
                if tokens[start][1] < left and right <= end]

    log_sites = [i for i in range(len(words) - 3)
                 if words[i] == "Log" and words[i + 1] == "."
                 and IDENT.fullmatch(words[i + 2]) and words[i + 3] == "("]
    log_strings = [literal for i in log_sites for literal in strings_in_call(i, i + 3)]
    reset_sites = calls("userResetIntervened")
    reset_labels = [literal for i in reset_sites for literal in strings_in_call(i, i + 1)]
    if len(reset_labels) != len(reset_sites):
        raise ValueError("expected exactly one literal label per userResetIntervened call")

    reads = {}
    for name in VOLATILES:
        reads[name] = sum(
            1 for i in indices([name])
            if (i == 0 or words[i - 1] not in ("var", "val"))
            and (i + 1 == len(words) or words[i + 1] != "=")
        )

    return {
        "log_calls": len(log_sites),
        "log_string_literals_multiset": dict(sorted(collections.Counter(log_strings).items())),
        "userResetIntervened_labels_multiset": dict(sorted(collections.Counter(reset_labels).items())),
        "synchronized_calls": len(calls("synchronized")),
        "Synchronized_annotations": len(indices(["@", "Synchronized"])),
        "expectedResetEpoch_epochAtTickStart": len(indices(["expectedResetEpoch", "=", "epochAtTickStart"])),
        "hook_calls": {name: len(calls(name)) for name in HOOKS},
        "k5_calls": {name: len(calls(name)) for name in K5_CALLS},
        "k5_property_reads": {name: len(indices(name_tokens(name))) for name in K5_PROPERTIES},
        "k5_volatile_reads": reads,
        "s2_calls": {name: len(calls(name)) for name in (
            "shouldSuppressS2RecRefire", "s2RecRefireStateAfterFiring")},
        "previousBankingForeground_assignments": len(indices([
            "previousBankingForeground", "=", "bankingForeground"])),
        "return_collect": len(indices(["return", "@", "collect"])),
    }


def main(argv):
    if len(argv) != 2:
        print("usage: python -I static_invariants.py Coordinator.kt", file=sys.stderr)
        return 2
    try:
        # read_text() would translate CRLF. Decode bytes strictly instead.
        source = Path(argv[1]).read_bytes().decode("utf-8", errors="strict")
        result = inventory(source)
    except (OSError, UnicodeError, ValueError) as error:
        print(f"static_invariants: {error}", file=sys.stderr)
        return 2
    # ASCII JSON escapes round-trip the original Unicode and avoid console encoding loss.
    print(json.dumps(result, ensure_ascii=True, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
