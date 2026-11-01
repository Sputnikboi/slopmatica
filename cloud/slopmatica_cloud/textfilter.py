"""A small word filter for text users write on the site (post titles and descriptions, applications,
reports, storage requests).

It is deliberately short and not exhaustive: it stops the obvious cases, the admins handle the rest
through reports. Matching works on whole words after normalising case, accents, leetspeak (``sh1t``),
stretched letters (``fuuuck``) and spaced-out letters (``f u c k``), so words that merely contain a
listed word ("Scunthorpe", "class", "grape") never match.

Extend BLOCKED below. A plain entry matches that exact word; an entry ending in ``*`` matches any
word that starts with it (``fuck*`` also catches "fucking"). Entries are lowercase ASCII letters.
"""

from __future__ import annotations

import re
import unicodedata
from typing import Callable, Iterable

BLOCKED: tuple[str, ...] = (
    "fuck*", "motherfuck*", "shit", "shits", "shitty", "shithead*", "bullshit*",
    "cunt*", "bitch*", "whore*", "slut*", "dickhead*", "cocksuck*",
    "nigger*", "nigga", "niggas", "niggaz", "faggot*", "fag", "fags",  # not "nigga*": "niggard"
    "retard", "retards", "retarded", "tranny", "trannies",
    "kys", "kill yourself",
)

# leetspeak and look-alikes, applied after lowercasing
_LEET = str.maketrans({
    "0": "o", "1": "i", "!": "i", "|": "i", "3": "e", "4": "a", "@": "a", "5": "s", "$": "s", "7": "t", "8": "b",
})
_WORD = re.compile(r"[a-z]+")
_STRETCH = re.compile(r"([a-z])\1{2,}")  # three or more of a letter: "fuuuck" -> "fuck"
# single letters separated by spaces or punctuation, at least three of them: "f u c k", "s.h.i.t"
_SPACED = re.compile(r"(?<![a-z])(?:[a-z][\s._*-]+){2,}[a-z](?![a-z])")
# a candidate Minecraft name in the original text
NAME_RE = re.compile(r"[A-Za-z0-9_]{3,16}")


def _compile(entries: Iterable[str]) -> tuple[frozenset[str], tuple[str, ...], tuple[tuple[str, ...], ...]]:
    exact, prefixes, phrases = set(), [], []
    for e in entries:
        if " " in e:
            phrases.append(tuple(e.split()))
        elif e.endswith("*"):
            prefixes.append(e[:-1])
        else:
            exact.add(e)
    return frozenset(exact), tuple(prefixes), tuple(phrases)


_EXACT, _PREFIXES, _PHRASES = _compile(BLOCKED)


def normalize(text: str) -> list[str]:
    """The words of text, lowercased, without accents, leetspeak or stretched letters."""
    text = unicodedata.normalize("NFKD", text.casefold())
    text = "".join(c for c in text if not unicodedata.combining(c)).translate(_LEET)
    text = _SPACED.sub(lambda m: re.sub(r"[^a-z]", "", m.group(0)), text)
    text = _STRETCH.sub(r"\1", text)
    return _WORD.findall(text)


def _word_hit(word: str) -> bool:
    return word in _EXACT or any(word.startswith(p) for p in _PREFIXES)


def find_blocked(text: str) -> str | None:
    """The first blocked word or phrase in text (normalised), or None if it is clean."""
    words = normalize(text)
    for i, w in enumerate(words):
        if _word_hit(w):
            return w
        for phrase in _PHRASES:
            if tuple(words[i:i + len(phrase)]) == phrase:
                return " ".join(phrase)
    return None


def check(text: str, known_names: Callable[[set[str]], set[str]] | None = None) -> str | None:
    """Like find_blocked, but Minecraft names Mojang verified are never held against the text.
    known_names gets the lowercased name-like words of text and returns the ones that are the names
    of real accounts; those are blanked out before the text is checked again. It is only called when
    the text would otherwise be rejected."""
    hit = find_blocked(text)
    if hit is None or known_names is None:
        return hit
    candidates = {m.group(0).lower() for m in NAME_RE.finditer(text)}
    names = known_names(candidates) if candidates else set()
    if not names:
        return hit
    cleaned = NAME_RE.sub(lambda m: " " if m.group(0).lower() in names else m.group(0), text)
    return find_blocked(cleaned)
