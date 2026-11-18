import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from packlib.group_alias_pipeline import add_group_aliases
from packlib.group_splits import split_reviewed_groups


def digest(*ids):
    return hashlib.sha256("\n".join(sorted(ids)).encode()).hexdigest()


class GroupSplitTests(unittest.TestCase):
    def setUp(self):
        self.items = {
            "mal:1": {"title": "Lupin III: First"},
            "mal:2": {"title": "Lupin III: Second"},
            "mal:3": {"title": "Meitantei Conan OVA 1"},
            "mal:4": {"title": "Edogawa Conan Special"},
            "mal:5": {"title": "Lupin III vs. Meitantei Conan"},
        }
        self.source_id = "group:mal:1:SPECIAL"
        self.group = {"id": self.source_id, "displayTitle": "Lupin III: First",
                      "groupType": "relations", "representativeMediaId": "mal:1",
                      "popularity": 42.0, "providerGroupId": self.source_id,
                      "members": [{"mediaId": mid, "position": index}
                                  for index, mid in enumerate(self.items)]}
        self.spec = {self.source_id: {
            "sourceIdsSha256": digest(*self.items), "ungrouped": ["mal:5"],
            "parts": [
                {"id": self.source_id, "displayTitle": "Lupin III: Specials",
                 "titlePrefixes": ["Lupin III:"], "memberIdsSha256": digest("mal:1", "mal:2")},
                {"id": "group:mal:3:SPECIAL", "displayTitle": "Meitantei Conan: Specials",
                 "titlePrefixes": ["Meitantei Conan", "Edogawa Conan"],
                 "memberIdsSha256": digest("mal:3", "mal:4")},
            ]}}

    def test_split_keeps_each_franchise_and_leaves_crossover_individual(self):
        groups, report = split_reviewed_groups([self.group], self.items, self.spec)
        self.assertEqual([group["id"] for group in groups],
                         [self.source_id, "group:mal:3:SPECIAL"])
        self.assertEqual([[member["mediaId"] for member in group["members"]]
                          for group in groups], [["mal:1", "mal:2"], ["mal:3", "mal:4"]])
        self.assertEqual([[member["position"] for member in group["members"]]
                          for group in groups], [[0, 1], [0, 1]])
        self.assertEqual([group["representativeMediaId"] for group in groups],
                         ["mal:1", "mal:3"])
        self.assertTrue(all("popularity" not in group for group in groups))
        self.assertEqual(report[0]["ungrouped"], ["mal:5"])

    def test_source_changes_and_collisions_fail_closed(self):
        changed = {**self.group, "members": self.group["members"][:-1]}
        with self.assertRaisesRegex(ValueError, "source members changed"):
            split_reviewed_groups([changed], self.items, self.spec)
        changed_items = {**self.items, "mal:4": {"title": "Another work"}}
        with self.assertRaisesRegex(ValueError, "members changed or overlap"):
            split_reviewed_groups([self.group], changed_items, self.spec)
        duplicate = {"id": "group:mal:3:SPECIAL"}
        with self.assertRaisesRegex(ValueError, "already exists"):
            split_reviewed_groups([self.group, duplicate], self.items, self.spec)

    def test_pipeline_writes_both_groups_and_correct_counts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / "items.jsonl"
            groups = root / "groups.jsonl"
            catalog.write_text("".join(json.dumps({"id": mid, **item}) + "\n"
                                       for mid, item in self.items.items()))
            groups.write_text(json.dumps(self.group) + "\n")
            files = {"catalog/items.jsonl": catalog, "groups/groups.jsonl": groups}
            summary = add_group_aliases(files, root, root / "audit", 1,
                                        reviewed_splits=self.spec)
            output = [json.loads(line) for line in files["groups/groups.jsonl"].read_text().splitlines()]
            self.assertEqual([record["displayTitle"] for record in output],
                             ["Lupin III: Specials", "Meitantei Conan: Specials"])
            self.assertEqual((summary["groupCount"], summary["relationshipCount"]), (2, 4))
            self.assertEqual(len((root / "audit/pack-1-splits.jsonl").read_text().splitlines()), 1)


if __name__ == "__main__":
    unittest.main()
