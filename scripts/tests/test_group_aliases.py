import json
import unittest
import tempfile
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from packlib.group_aliases import derive_group_candidates, resolve_collisions
from packlib.group_alias_pipeline import add_group_aliases


def group(title, ids, kind="seasons"):
    return {"id": "g", "displayTitle": title, "groupType": kind,
            "members": [{"mediaId": member_id} for member_id in ids]}


def item(title, *aliases):
    return {"title": title, "aliases": [{"title": alias} for alias in aliases]}


class GroupAliasTests(unittest.TestCase):
    def test_parallel_seasons_and_unicode(self):
        records = {"a": item("Example — Season 1", "例子 — Season 1"),
                   "b": item("Example — Season 2", "例子 — Season 2")}
        result = derive_group_candidates(group("Example", records, "relations"), records)
        self.assertEqual([(r["alias"], r["status"]) for r in result], [("例子", "accepted")])

    def test_complete_alternative_title_may_end_with_series(self):
        records = {str(n): item(f"Men in Black — Season {n}",
                                f"Men in Black The Animated Series — Season {n}")
                   for n in (1, 2)}
        candidate = derive_group_candidates(group("Men in Black", records), records)[0]
        self.assertEqual((candidate["alias"], candidate["status"]),
                         ("Men in Black The Animated Series", "accepted"))

    def test_parallel_short_seasons_accept_declared_alternative(self):
        records = {str(n): item(f"Example — Season {n}", f"Alternative — Season {n}")
                   for n in range(1, 5)}
        self.assertEqual(derive_group_candidates(group("Example", records), records)[0]["status"],
                         "accepted")
        for size in (2, 3):
            subset = dict(list(records.items())[:size])
            candidate = derive_group_candidates(group("Example", subset), subset)[0]
            self.assertEqual(candidate["status"], "accepted")
            self.assertEqual(candidate["support"], size)

    def test_short_season_specific_titles_stay_ambiguous(self):
        for alternative in ("Alternative 2nd Season", "Alternative Final Season",
                            "Alternative 3rdシーズン", "Alternative II",
                            "Alternative Dai Ni Maku", "Alternative 第二季",
                            "Alternative OVA", "Alternative S3", "Alternative 第2クール",
                            "Alternative 新編集版"):
            records = {str(n): item(f"Example — Season {n}",
                                    f"{alternative} — Season {n}") for n in (1, 2)}
            candidate = derive_group_candidates(group("Example", records), records)[0]
            self.assertNotEqual(candidate["status"], "accepted", alternative)

    def test_partial_season_translation_and_specific_title(self):
        records = {str(n): item(f"Original — Season {n}",
                                *((f"Traducción — Season {n}",) if n < 4 else ()))
                   for n in range(1, 6)}
        translated = derive_group_candidates(group("Original", records), records)[0]
        self.assertEqual((translated["support"], translated["status"]), (3, "accepted"))
        specific = {str(n): item(f"Original — Season {n}",
                                 f"Traducción 3rd Season — Season {n}")
                    for n in range(1, 6)}
        self.assertFalse(any(c["status"] == "accepted" for c in
                             derive_group_candidates(group("Original", specific), specific)))

    def test_copied_episode_title_is_not_a_series_alias(self):
        records = {str(n): item(f"Original — Season {n}",
                                f"Translation No. 170+1: More — Season {n}")
                   for n in range(1, 5)}
        self.assertFalse(any(c["status"] == "accepted" for c in
                             derive_group_candidates(group("Original", records), records)))

    def test_short_season_unanchored_us_title_stays_ambiguous(self):
        records = {str(n): {"title": f"Quick Draw McGraw — Season {n}",
                            "aliases": [{"title": f"Augie Doggie — Season {n}", "kind": "US"}]}
                   for n in (1, 2)}
        candidate = derive_group_candidates(group("Quick Draw McGraw", records), records)[0]
        self.assertEqual(candidate["status"], "ambiguous")
        self.assertIn("different programme", candidate["reason"])

    def test_short_season_near_title_collision_is_rejected(self):
        records = {str(n): item(f"Old Show — Season {n}",
                                f"New: Show — Season {n}") for n in (1, 2)}
        candidates = derive_group_candidates(group("Old Show", records), records)
        other = {"id": "other", "displayTitle": "New Show"}
        resolved = resolve_collisions([group("Old Show", records), other], candidates)
        self.assertEqual(resolved[0]["status"], "rejected")

    def test_new_short_alias_does_not_displace_established_alias(self):
        established = {"groupId": "older", "normalized": "translated title", "status": "accepted",
                       "reason": "parallel members and sufficient coverage"}
        proposed = {"groupId": "newer", "normalized": "translated title", "status": "accepted",
                    "reason": "plausible parallel root of every short season"}
        groups = [{"id": "older", "displayTitle": "Original A"},
                  {"id": "newer", "displayTitle": "Original B"}]
        resolve_collisions(groups, [established, proposed])
        self.assertEqual(established["status"], "accepted")
        self.assertEqual(proposed["status"], "rejected")

    def test_one_member_or_one_alias_is_not_sufficient(self):
        records = {"a": item("Example", "Alternative"),
                   "b": item("Example — Season 2", "Second part")}
        result = derive_group_candidates(group("Example", records), records)
        self.assertTrue(result)
        self.assertTrue(all(r["status"] != "accepted" for r in result))

    def test_shared_variant_alias_requires_every_member(self):
        records = {"a": item("Webcomic", "Comic translation"),
                   "b": item("Comic edition", "Comic translation")}
        result = derive_group_candidates(group("Webcomic", records, "variants"), records)
        self.assertEqual(result[0]["status"], "accepted")
        self.assertEqual(result[0]["support"], 2)

    def test_parallel_volumes_preserve_non_latin_root(self):
        records = {"a": item("Work Volume 1", "作品 Volume 1"),
                   "b": item("Work Volume 2", "作品 Volume 2")}
        result = derive_group_candidates(group("Work", records, "relations"), records)
        self.assertEqual(result[0]["alias"], "作品")
        self.assertEqual(result[0]["status"], "accepted")

    def test_variant_alias_on_only_two_of_three_members_remains_ambiguous(self):
        records = {"a": item("Webcomic", "Shared title"),
                   "b": item("Publication", "Shared title"),
                   "c": item("Colored edition", "Different title")}
        self.assertFalse(any(r["status"] == "accepted" for r in
                             derive_group_candidates(group("Webcomic", records, "variants"), records)))

    def test_specific_subtitle_is_excluded_even_when_every_season_repeats_it(self):
        records = {str(n): item(f"Show — Season {n}", f"Show: Special — Season {n}")
                   for n in range(1, 5)}
        result = derive_group_candidates(group("Show", records), records)
        self.assertEqual(result[0]["status"], "rejected")
        self.assertIn("specific subtitle", result[0]["reason"])

    def test_specific_installment_number_is_not_group_name(self):
        records = {str(n): item(f"Show — Season {n}", f"Alternate 2 — Season {n}")
                   for n in range(1, 5)}
        result = derive_group_candidates(group("Show", records), records)
        self.assertEqual(result[0]["status"], "rejected")
        self.assertIn("installment marker", result[0]["reason"])

    def test_subtitle_extending_another_group_alias_is_ambiguous(self):
        records = {str(n): item(f"Show — Season {n}", f"Translation — Season {n}",
                                 f"Translation: Special Edition — Season {n}") for n in range(1, 5)}
        result = {r["alias"]: r for r in derive_group_candidates(group("Show", records), records)}
        self.assertEqual(result["Translation"]["status"], "accepted")
        self.assertEqual(result["Translation: Special Edition"]["status"], "ambiguous")

    def test_collection_repeated_installment_alias_is_not_group_alias(self):
        records = {"a": item("Film One", "Film One translated"),
                   "b": item("Film Two", "Film One translated")}
        self.assertFalse(any(r["status"] == "accepted" for r in
                             derive_group_candidates(group("Films Collection", records, "collection"), records)))

    def test_subject_alias_is_never_a_name(self):
        records = {"a": {"title": "Books", "aliases": [{"title": "Witches", "kind": "SUBJECT"}]},
                   "b": {"title": "Books — Book 2", "aliases": [{"title": "Witches", "kind": "SUBJECT"}]}}
        self.assertEqual(derive_group_candidates(group("Books", records), records), [])

    def test_collision_and_input_order(self):
        records = {"a": item("Example — Season 1", "Alternative — Season 1"),
                   "b": item("Example — Season 2", "Alternative — Season 2")}
        first = derive_group_candidates(group("Example", ["a", "b"]), records)
        second = derive_group_candidates(group("Example", ["b", "a"]), records)
        self.assertEqual(first, second)
        resolved = resolve_collisions([group("Example", records),
                                       {"id": "other", "displayTitle": "Alternative"}], first)
        self.assertEqual(resolved[0]["status"], "rejected")

    def test_alias_order_does_not_change_evidence(self):
        first = {"a": item("Work", "Alternate", "ALTERNATE"),
                 "b": item("Work Volume 2", "Alternate Volume 2", "ALTERNATE Volume 2")}
        reordered = {key: {**value, "aliases": list(reversed(value["aliases"]))}
                     for key, value in first.items()}
        self.assertEqual(derive_group_candidates(group("Work", first, "relations"), first),
                         derive_group_candidates(group("Work", reordered, "relations"), reordered))

    def test_explicit_group_alias_requires_current_app_even_without_derived_alias(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / "items.jsonl"
            groups = root / "groups.jsonl"
            catalog.write_text('{"id":"a","title":"Item"}\n', encoding="utf-8")
            groups.write_text('{"id":"g","displayTitle":"Group","members":[{"mediaId":"a"}],'
                              '"aliases":[{"title":"Declared name"}]}\n', encoding="utf-8")
            summary = add_group_aliases({"catalog/items.jsonl": catalog,
                                         "groups/groups.jsonl": groups}, root, root / "audit", 99)
            self.assertTrue(summary["hasGroupAliasField"])
            self.assertEqual(summary["groupAliases"], 0)

    def test_review_hold_keeps_suspect_short_series_alias_ambiguous(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / "items.jsonl"
            groups = root / "groups.jsonl"
            catalog.write_text('\n'.join(
                '{"id":"%s","title":"Show — Season %s","aliases":'
                '[{"title":"Other Show — Season %s","kind":"FR"}]}' % (n, n, n)
                for n in (1, 2)) + '\n', encoding="utf-8")
            groups.write_text('{"id":"g","displayTitle":"Show","groupType":"seasons",'
                              '"members":[{"mediaId":"1"},{"mediaId":"2"}]}\n', encoding="utf-8")
            summary = add_group_aliases({"catalog/items.jsonl": catalog,
                                         "groups/groups.jsonl": groups}, root, root / "audit", 4,
                                        {"g": None})
            self.assertEqual(summary["groupAliases"], 0)
            self.assertIn("manual group identity review", (root / "audit" / "pack-4.jsonl").read_text())

    def test_reviewed_alias_requires_declared_member_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / "items.jsonl"
            groups = root / "groups.jsonl"
            catalog.write_text('{"id":"a","title":"Work","aliases":'
                               '[{"title":"Traducción"}]}\n'
                               '{"id":"b","title":"Work: Sequel"}\n', encoding="utf-8")
            groups.write_text('{"id":"g","displayTitle":"Work","groupType":"relations",'
                              '"members":[{"mediaId":"a"},{"mediaId":"b"}]}\n',
                              encoding="utf-8")
            files = {"catalog/items.jsonl": catalog, "groups/groups.jsonl": groups}
            summary = add_group_aliases(files, root, root / "audit", 99,
                                        reviewed_aliases={"g": ["Traducción"]})
            self.assertEqual(summary["groupAliases"], 1)
            self.assertEqual(json.loads(files["groups/groups.jsonl"].read_text())
                             ["aliases"][0]["title"], "Traducción")
            with self.assertRaisesRegex(ValueError, "lacks safe source evidence"):
                add_group_aliases({"catalog/items.jsonl": catalog,
                                   "groups/groups.jsonl": groups}, root, root / "second", 99,
                                  reviewed_aliases={"g": ["Invented"]})


if __name__ == "__main__":
    unittest.main()
