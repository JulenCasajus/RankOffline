"""Pure offline grouping invariants. Run with python3 -m unittest discover -s tools."""
import unittest

from group_catalog import assign_groups, normalized_format


def anime(number, fmt="TV", year=2020, relations=(), episodes=12, adaptations=()):
    return {
        "id": f"mal:{number}", "anilistId": number, "title": f"Anime {number}",
        "format": fmt, "seasonYear": year, "episodes": episodes,
        "relations": (
            [{"type": kind, "anilistId": target} for kind, target in relations]
            + [{"type": "ADAPTATION", "anilistId": target} for target in adaptations]
        ),
    }


class GroupCatalogTest(unittest.TestCase):
    def test_family_is_split_into_normalized_format_groups(self):
        rows = [
            anime(1, relations=[("SEQUEL", 2), ("SIDE_STORY", 3), ("SIDE_STORY", 4)]),
            anime(2, "ONA", 2021), anime(3, "MOVIE", 2022, [("SEQUEL", 5)]),
            anime(4, "OVA", 2020, [("PARENT", 1), ("SEQUEL", 6)]), anime(5, "MOVIE", 2023), anime(6, "OVA", 2021),
        ]
        stats = assign_groups(rows)
        self.assertEqual({"MOVIES": 1, "SERIES": 1, "SPECIAL": 1}, stats["groupsByFormat"])
        self.assertEqual("group:mal:1:SERIES", rows[0]["groupId"])
        self.assertEqual(rows[0]["groupId"], rows[1]["groupId"])
        self.assertEqual("group:mal:1:MOVIES", rows[2]["groupId"])
        self.assertEqual(rows[2]["groupId"], rows[4]["groupId"])
        self.assertNotEqual(rows[0]["groupId"], rows[2]["groupId"])
        self.assertEqual("mal:3", rows[2]["groupCoverAnimeId"])
        self.assertEqual("Anime 3", rows[2]["groupDisplayTitle"])

    def test_series_bucket_normalizes_tv_short_and_ona(self):
        self.assertEqual("SERIES", normalized_format("TV"))
        self.assertEqual("SERIES", normalized_format("TV_SHORT"))
        self.assertEqual("SERIES", normalized_format("ONA"))
        self.assertEqual("MOVIES", normalized_format("MOVIE"))
        self.assertEqual("SPECIAL", normalized_format("OVA"))
        self.assertEqual("SPECIAL", normalized_format("SPECIAL"))
        self.assertEqual("MUSIC", normalized_format("MUSIC"))

    def test_display_uses_earliest_member_in_bucket_but_identity_keeps_family_root(self):
        rows = [
            anime(1, "MOVIE", 2015, [("SIDE_STORY", 2), ("SEQUEL", 3)]),
            anime(2, "TV", 2013, [("SEQUEL", 4)]),
            anime(3, "MOVIE", 2016),
            anime(4, "ONA", 2020),
        ]
        rows[0]["title"] = "Movie title must not name Series"
        rows[1]["title"] = "Main Series"
        assign_groups(rows)
        self.assertEqual("group:mal:2:SERIES", rows[1]["groupId"])
        self.assertEqual("Main Series", rows[1]["groupDisplayTitle"])
        self.assertEqual("mal:2", rows[1]["groupCoverAnimeId"])

    def test_ova_and_special_share_one_bucket(self):
        rows = [anime(40, "OVA", relations=[("SEQUEL", 41)]), anime(41, "SPECIAL")]
        assign_groups(rows)
        self.assertEqual("SPECIAL", rows[0]["groupFormat"])
        self.assertEqual(rows[0]["groupId"], rows[1]["groupId"])

    def test_single_format_member_is_not_wrapped(self):
        rows = [anime(10, relations=[("SIDE_STORY", 11)]), anime(11, "MOVIE")]
        stats = assign_groups(rows)
        self.assertEqual(0, stats["groups"])
        self.assertTrue(all("groupId" not in row for row in rows))

    def test_excluded_relations_do_not_create_family(self):
        rows = [anime(20, relations=[("SPIN_OFF", 21), ("SAME_UNIVERSE", 22)]), anime(21), anime(22)]
        self.assertEqual(0, assign_groups(rows)["groups"])
        self.assertTrue(all("groupId" not in row for row in rows))

    def test_group_id_is_stable_when_title_changes(self):
        rows = [anime(30, relations=[("SEQUEL", 31)]), anime(31, year=2022)]
        assign_groups(rows)
        before = rows[0]["groupId"]
        rows[0]["title"] = "Different localized title"
        assign_groups(rows)
        self.assertEqual(before, rows[0]["groupId"])

    def test_multi_parent_crossover_does_not_merge_unrelated_families(self):
        rows = [
            anime(100, relations=[("SEQUEL", 101)]),
            anime(101, year=2021),
            anime(200, relations=[("SEQUEL", 201)]),
            anime(201, year=2022),
            anime(300, "ONA", 2023, [("PARENT", 100), ("PARENT", 200)]),
        ]

        stats = assign_groups(rows)

        self.assertEqual(rows[0]["groupId"], rows[1]["groupId"])
        self.assertEqual(rows[2]["groupId"], rows[3]["groupId"])
        self.assertNotEqual(rows[0]["groupId"], rows[2]["groupId"])
        self.assertNotIn("groupId", rows[4])
        self.assertEqual(2, stats["ambiguousParentEdgesSkipped"])

    def test_single_parent_relation_still_attaches_related_content(self):
        rows = [
            anime(400, relations=[("SEQUEL", 401)]),
            anime(401, year=2021),
            anime(402, "ONA", 2022, [("PARENT", 400)]),
        ]

        assign_groups(rows)

        self.assertEqual(rows[0]["groupId"], rows[1]["groupId"])
        self.assertEqual(rows[0]["groupId"], rows[2]["groupId"])

    def test_excluded_reverse_relation_overrides_parent_relation(self):
        rows = [
            anime(500, relations=[("SEQUEL", 501), ("SPIN_OFF", 502)]),
            anime(501, year=2021),
            anime(502, "ONA", 2022, [("PARENT", 500)]),
        ]

        stats = assign_groups(rows)

        self.assertEqual(rows[0]["groupId"], rows[1]["groupId"])
        self.assertNotIn("groupId", rows[2])
        self.assertEqual(1, stats["excludedPairEdgesSkipped"])

    def test_single_episode_ona_prequel_does_not_become_a_tv_season(self):
        rows = [
            anime(600, "ONA", 2024, [("SEQUEL", 601)], episodes=1),
            anime(601, "TV", 1999, [("PREQUEL", 600)], episodes=100),
        ]

        stats = assign_groups(rows)

        self.assertTrue(all("groupId" not in row for row in rows))
        self.assertEqual(2, stats["shortFormMainlineEdgesSkipped"])

    def test_multi_episode_ona_can_remain_in_a_main_series_chain(self):
        rows = [
            anime(700, "TV", 2020, [("SEQUEL", 701)]),
            anime(701, "ONA", 2021, [("PREQUEL", 700)], episodes=6),
        ]

        assign_groups(rows)

        self.assertEqual(rows[0]["groupId"], rows[1]["groupId"])

    def test_series_side_story_is_not_treated_as_a_season(self):
        rows = [
            anime(800, "TV", 2020, [("SIDE_STORY", 801)]),
            anime(801, "ONA", 2021, [("PARENT", 800)], episodes=6),
        ]

        assign_groups(rows)

        self.assertTrue(all("groupId" not in row for row in rows))

    def test_summary_cannot_reenter_family_through_another_allowed_edge(self):
        rows = [
            anime(900, "TV", 2020, [("SUMMARY", 902), ("SIDE_STORY", 901)]),
            anime(901, "SPECIAL", 2021, [("SEQUEL", 902)]),
            anime(902, "TV", 2022, [("PARENT", 900), ("PREQUEL", 901)]),
            anime(903, "TV", 2023, [("PREQUEL", 900)]),
        ]

        stats = assign_groups(rows)

        self.assertEqual(rows[0]["groupId"], rows[3]["groupId"])
        self.assertNotIn("groupId", rows[2])
        self.assertGreater(stats["derivativeNodeEdgesSkipped"], 0)

    def test_sequel_chain_splits_when_official_adaptation_source_changes(self):
        rows = [
            anime(1000, relations=[("SEQUEL", 1001)], adaptations=[5000]),
            anime(
                1001, year=2007,
                relations=[("PREQUEL", 1000), ("SEQUEL", 1002)],
                adaptations=[5000, 5001],
            ),
            anime(
                1002, year=2017,
                relations=[("PREQUEL", 1001), ("SEQUEL", 1003)],
                adaptations=[6000, 6001],
            ),
            anime(1003, year=2025, relations=[("PREQUEL", 1002)], adaptations=[6001]),
        ]

        stats = assign_groups(rows)

        self.assertEqual(rows[0]["groupId"], rows[1]["groupId"])
        self.assertEqual(rows[2]["groupId"], rows[3]["groupId"])
        self.assertNotEqual(rows[0]["groupId"], rows[2]["groupId"])
        self.assertEqual(2, stats["conflictingAdaptationEdgesSkipped"])

    def test_unknown_adaptation_source_does_not_break_explicit_sequel_chain(self):
        rows = [
            anime(1100, relations=[("SEQUEL", 1101)], adaptations=[7000]),
            anime(1101, year=2021, relations=[("PREQUEL", 1100)]),
        ]

        assign_groups(rows)

        self.assertEqual(rows[0]["groupId"], rows[1]["groupId"])


if __name__ == "__main__":
    unittest.main()
