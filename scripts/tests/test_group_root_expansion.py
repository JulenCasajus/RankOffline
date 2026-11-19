import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from packlib.group_aliases import derive_group_candidates
from packlib.group_alias_pipeline import add_group_aliases
from packlib.group_title_roots import group_title_roots


def group(title, *members, kind="relations"):
    return {"id": "g", "displayTitle": title, "groupType": kind,
            "members": [{"mediaId": member} for member in members]}


def item(title, *aliases):
    return {"title": title, "aliases": [{"title": alias} for alias in aliases]}


class GroupRootExpansionTests(unittest.TestCase):
    def test_direct_root_alias_with_later_season(self):
        items = {"a": item("Original", "Traducción"),
                 "b": item("Original: Season 2")}
        candidate = derive_group_candidates(group("Original", "a", "b"), items)[0]
        self.assertEqual((candidate["alias"], candidate["status"]), ("Traducción", "accepted"))

    def test_direct_root_does_not_accept_member_specific_alias(self):
        for alias in ("Traducción Season 1", "Traducción First Stage",
                      "Traducción 上部", "Original: Red", "Original 1"):
            items = {"a": item("Original", alias), "b": item("Original: Season 2")}
            self.assertFalse(any(candidate["status"] == "accepted" for candidate in
                                 derive_group_candidates(group("Original", "a", "b"), items)), alias)

    def test_plain_prefix_still_needs_review(self):
        items = {"a": item("Dragon Ball", "Bola de Dragón"),
                 "b": item("Dragon Ball Z")}
        self.assertFalse(any(candidate["status"] == "accepted" for candidate in
                             derive_group_candidates(group("Dragon Ball", "a", "b"), items)))

    def test_ordinal_season_and_bare_number_are_structural(self):
        for sequel in ("Original 2nd Season", "Original 2"):
            items = {"a": item("Original", "Traducción"), "b": item(sequel)}
            self.assertEqual(derive_group_candidates(group("Original", "a", "b"), items)[0]
                             ["status"], "accepted")

    def test_parallel_numbered_aliases_expose_only_common_name(self):
        items = {"a": item("Original", "Saiki K 1"),
                 "b": item("Original 2", "Saiki K 3")}
        source = {**group("Original", "a", "b"), "id": "g:SERIES"}
        aliases = {c["alias"]: c for c in derive_group_candidates(source, items)}
        self.assertEqual(aliases["Saiki K"]["status"], "ambiguous")
        self.assertIn("semantic review", aliases["Saiki K"]["reason"])
        self.assertNotEqual(aliases["Saiki K 1"]["status"], "accepted")

    def test_numbered_root_never_keeps_an_unfinished_season_label(self):
        source = {**group("Original", "a", "b"), "id": "g:SERIES"}
        for marker in ("Season", "Part", "ภาค", "พาร์ต", "עונה", "Сезон"):
            items = {"a": item("Original", f"Translated {marker} 1"),
                     "b": item("Original 2", f"Translated {marker} 2")}
            aliases = {c["alias"]: c for c in derive_group_candidates(source, items)}
            self.assertEqual(aliases[f"Translated {marker}"]["status"], "rejected")
        same = {"a": item("Original", "Original 1"),
                "b": item("Original 2", "Original 2")}
        self.assertFalse(any(c["alias"] == "Original" for c in
                             derive_group_candidates(source, same)))

    def test_declared_root_alias_can_be_corroborated_by_numbered_members(self):
        source = {**group("Original", "a", "b"), "id": "g:SERIES"}
        items = {"a": item("Original", "Translation"),
                 "b": item("Original 2", "Translation 3")}
        candidate = next(c for c in derive_group_candidates(source, items)
                         if c["alias"] == "Translation")
        self.assertEqual(candidate["status"], "accepted")
        self.assertIn("corroborated", candidate["reason"])
        items["a"] = item("Original", "Translation 1")
        candidate = next(c for c in derive_group_candidates(source, items)
                         if c["alias"] == "Translation")
        self.assertEqual(candidate["status"], "ambiguous")

    def test_related_root_excludes_specific_special_or_movie_alias(self):
        items = {"a": item("Work: First Special", "Translated first special"),
                 "b": item("Work: Second Special")}
        source = {**group("Work: First Special", "a", "b"),
                  "id": "group:mal:1:SPECIAL"}
        self.assertFalse(any(candidate["status"] == "accepted" for candidate in
                             derive_group_candidates(source, items)))

    def test_distinct_subtitles_produce_group_root_and_parallel_alias(self):
        items = {"a": item("Original: Red", "Traducción: Roja"),
                 "b": item("Original: Blue", "Traducción: Azul")}
        source = group("Original: Red", "a", "b")
        self.assertEqual(group_title_roots([source], items, []), {"g": "Original"})
        candidates = derive_group_candidates(source, items, "Original")
        translated = next(candidate for candidate in candidates if candidate["alias"] == "Traducción")
        self.assertEqual(translated["status"], "accepted")

    def test_short_generic_prefix_is_not_a_group_translation(self):
        items = {"a": item("Fate/kaleid liner Movie: Red", "Fate: Red"),
                 "b": item("Fate/kaleid liner Movie: Blue", "Fate: Blue")}
        source = group("Fate/kaleid liner Movie: Red", "a", "b")
        candidates = derive_group_candidates(source, items, "Fate/kaleid liner Movie")
        self.assertFalse(any(candidate["alias"] == "Fate" and candidate["status"] == "accepted"
                             for candidate in candidates))

    def test_same_subtitle_with_new_season_does_not_lose_its_identity(self):
        items = {"a": item("Saga: Arc One"), "b": item("Saga: Arc One Season 2")}
        self.assertEqual(group_title_roots([group("Saga: Arc One", "a", "b")], items, []), {})

    def test_existing_group_or_alias_blocks_generic_root(self):
        items = {"a": item("Series: Red"), "b": item("Series: Blue")}
        source = group("Series: Red", "a", "b")
        other = {"id": "other", "displayTitle": "Series"}
        self.assertEqual(group_title_roots([source, other], items, []), {})
        protected = [{"groupId": "other", "normalized": "series", "status": "accepted"}]
        self.assertEqual(group_title_roots([source], items, protected), {})

    def test_declared_collection_and_variant_titles_are_preserved(self):
        items = {"a": item("Work: Red"), "b": item("Work: Blue")}
        for kind in ("collection", "variants", "seasons"):
            self.assertEqual(group_title_roots([group("Work: Red", "a", "b", kind=kind)],
                                               items, []), {})
        declared = {**group("Artwork: Red", "a", "b"),
                    "aliases": [{"title": "Chosen group name"}]}
        self.assertEqual(group_title_roots([declared],
                                           {"a": item("Artwork: Red"), "b": item("Artwork: Blue")},
                                           []), {})

    def test_different_numbered_seasons_drop_number_from_group_title(self):
        items = {"a": item("Original 2nd Season"), "b": item("Original 3rd Season")}
        self.assertEqual(group_title_roots([group("Original 2nd Season", "a", "b")], items, []),
                         {"g": "Original"})
        same = {"a": item("Original 2nd Season"), "b": item("Original 2nd Season")}
        self.assertEqual(group_title_roots([group("Original 2nd Season", "a", "b")], same, []),
                         {})

    def test_homogeneous_specials_and_movies_use_collection_title(self):
        items = {"a": item("Violence Jack: Harem Bomber-hen"),
                 "b": item("Violence Jack: Jigoku Gai-hen")}
        special = {**group(items["a"]["title"], "a", "b"), "id": "g:SPECIAL"}
        self.assertEqual(group_title_roots([special], items, []),
                         {"g:SPECIAL": "Violence Jack: Specials"})
        movie = {**special, "id": "g:MOVIES"}
        self.assertEqual(group_title_roots([movie], items, []),
                         {"g:MOVIES": "Violence Jack: Movies"})
        film_items = {"a": item("Haikyuu!! Movie: First"),
                      "b": item("Haikyuu!! Movie: Second")}
        film_group = {**group("Haikyuu!! Movie: First", "a", "b"), "id": "h:MOVIES"}
        self.assertEqual(group_title_roots([film_group], film_items, []),
                         {"h:MOVIES": "Haikyuu!!: Movies"})
        progressive = {"a": item("Sword Art Online: Progressive Movie - First"),
                       "b": item("Sword Art Online: Progressive Movie - Second")}
        progressive_group = {**group(progressive["a"]["title"], "a", "b"),
                             "id": "s:MOVIES"}
        self.assertEqual(group_title_roots([progressive_group], progressive, []),
                         {"s:MOVIES": "Sword Art Online: Progressive Movies"})
        editions = {"a": item("Final Fantasy VII: Advent Children"),
                    "b": item("Final Fantasy VII: Advent Children Complete")}
        edition_group = {**group(editions["a"]["title"], "a", "b"),
                         "id": "e:MOVIES"}
        self.assertEqual(group_title_roots([edition_group], editions, []), {})
        mixed = {"a": items["a"], "b": item("Unrelated: Second special")}
        self.assertEqual(group_title_roots([special], mixed, []), {})

    def test_parallel_film_suffixes_expose_translated_root(self):
        items = {"a": item("Original Title: Reflection", "翻訳名 Reflection"),
                 "b": item("Original Title: Detonation", "翻訳名 Detonation")}
        group_record = {**group(items["a"]["title"], "a", "b"), "id": "g:MOVIES",
                        "displayTitle": "Original Title: Movies"}
        aliases = derive_group_candidates(group_record, items, "Original Title: Movies")
        self.assertEqual(next(x for x in aliases if x["alias"] == "翻訳名")["status"],
                         "accepted")

    def test_variant_title_uses_unmarked_work_with_alias_evidence(self):
        items = {"a": item("Ao no Hako", "Blue Box"),
                 "b": item("Blue Box (Oneshot)", "Ao no Hako")}
        source = group("Blue Box (Oneshot)", "a", "b", kind="variants")
        self.assertEqual(group_title_roots([source], items, []), {"g": "Ao no Hako"})
        items["b"] = item("Different (Oneshot)")
        self.assertEqual(group_title_roots([source], items, []), {})

    def test_reviewed_special_title_requires_parent_and_every_member(self):
        items = {str(n): item(f"Pokemon: Entry {n}") for n in range(9)}
        items["other"] = item("Pocket Monsters: Third", "Pokemon: Third")
        parent = {"id": "g:SERIES", "displayTitle": "Pokemon"}
        special = {**group("Pokemon: Entry 0", *items), "id": "g:SPECIAL"}
        approved = {"g:SPECIAL": "Pokemon: Specials"}
        self.assertEqual(group_title_roots([parent, special], items, [], approved), approved)
        with self.assertRaisesRegex(ValueError, "lacks member evidence"):
            group_title_roots([parent, special], {**items, "other": item("Unrelated")}, [],
                              approved)
        with self.assertRaisesRegex(ValueError, "differs from its declared parent"):
            group_title_roots([parent, special], items, [],
                              {"g:SPECIAL": "Pokemon XY: Specials"})

    def test_pipeline_writes_root_and_alias_without_changing_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / "items.jsonl"
            groups = root / "groups.jsonl"
            catalog.write_text('\n'.join(json.dumps({"id": key, **value}, ensure_ascii=False)
                                         for key, value in {"a": item("Original: Red", "Traducción: Roja"),
                                                            "b": item("Original: Blue", "Traducción: Azul")}.items())
                               + '\n', encoding="utf-8")
            groups.write_text(json.dumps(group("Original: Red", "a", "b")) + '\n', encoding="utf-8")
            files = {"catalog/items.jsonl": catalog, "groups/groups.jsonl": groups}
            summary = add_group_aliases(files, root, root / "audit", 99)
            written = json.loads(files["groups/groups.jsonl"].read_text().splitlines()[0])
            self.assertEqual((written["id"], written["displayTitle"]), ("g", "Original"))
            self.assertEqual([alias["title"] for alias in written["aliases"]], ["Traducción"])
            self.assertEqual(summary["rootTitleChanges"], 1)

    def test_oneshot_alias_keeps_item_edition_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / "items.jsonl"
            groups = root / "groups.jsonl"
            catalog.write_text('{"id":"a","title":"Ao no Hako",'
                               '"aliases":[{"title":"Blue Box"}]}\n'
                               '{"id":"b","title":"Blue Box (Oneshot)",'
                               '"aliases":[{"title":"Ao no Hako"}]}\n', encoding="utf-8")
            groups.write_text('{"id":"g","displayTitle":"Blue Box (Oneshot)",'
                              '"groupType":"variants","members":'
                              '[{"mediaId":"a"},{"mediaId":"b"}]}\n', encoding="utf-8")
            files = {"catalog/items.jsonl": catalog, "groups/groups.jsonl": groups}
            add_group_aliases(files, root, root / "audit", 99,
                              reviewed_aliases={"g": ["Blue Box"]})
            written_items = [json.loads(line) for line in files["catalog/items.jsonl"]
                             .read_text(encoding="utf-8").splitlines()]
            written_group = json.loads(files["groups/groups.jsonl"].read_text())
            self.assertEqual(written_items[1]["aliases"][0]["title"],
                             "Ao no Hako (Oneshot)")
            self.assertEqual(written_group["displayTitle"], "Ao no Hako")
            self.assertEqual(written_group["aliases"][0]["title"], "Blue Box")


if __name__ == "__main__":
    unittest.main()
