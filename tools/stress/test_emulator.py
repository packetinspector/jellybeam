import unittest
import xml.etree.ElementTree as ET

from emulator import DETAIL_MENU_DOOR, cycle_state_after_menu_close, cycle_state_on_home, focus_is


def tree(xml):
    return ET.fromstring(xml)


class FocusCyclePredicateTest(unittest.TestCase):
    def test_leaf_door_owning_focus_passes(self):
        t = tree(f'<hierarchy><node focusable="true" focused="true"><node text="{DETAIL_MENU_DOOR}"/></node></hierarchy>')
        self.assertTrue(focus_is(t, DETAIL_MENU_DOOR))

    def test_focused_ancestor_containing_the_door_fails(self):
        # A focused row that contains the (unfocused) door must not count as the door.
        t = tree(f'<hierarchy><node focusable="true" focused="true">'
                 f'<node focusable="true" text="Play"/><node focusable="true" text="{DETAIL_MENU_DOOR}"/>'
                 f'</node></hierarchy>')
        self.assertFalse(focus_is(t, DETAIL_MENU_DOOR))

    def test_focus_elsewhere_fails(self):
        t = tree(f'<hierarchy><node focusable="true" focused="true" text="Play"/>'
                 f'<node focusable="true" text="{DETAIL_MENU_DOOR}"/></hierarchy>')
        self.assertFalse(focus_is(t, DETAIL_MENU_DOOR))

    def test_menu_close_requires_focus_back_on_the_door(self):
        panel_gone_focus_on_play = tree(f'<hierarchy><node focusable="true" focused="true" text="Play"/>'
                                        f'<node focusable="true" text="{DETAIL_MENU_DOOR}"/></hierarchy>')
        self.assertFalse(cycle_state_after_menu_close(panel_gone_focus_on_play))
        panel_gone_focus_on_door = tree(f'<hierarchy><node focusable="true" text="Play"/>'
                                        f'<node focusable="true" focused="true" text="{DETAIL_MENU_DOOR}"/></hierarchy>')
        self.assertTrue(cycle_state_after_menu_close(panel_gone_focus_on_door))
        panel_still_open = tree(f'<hierarchy><node text="THIS TITLE"/>'
                                f'<node focusable="true" focused="true" text="{DETAIL_MENU_DOOR}"/></hierarchy>')
        self.assertFalse(cycle_state_after_menu_close(panel_still_open))

    def test_home_state_requires_the_home_shelf_marker(self):
        unrelated_focused_screen = tree('<hierarchy><node text="Settings"/><node focusable="true" focused="true" text="Home"/></hierarchy>')
        self.assertFalse(cycle_state_on_home(unrelated_focused_screen))
        home = tree('<hierarchy><node text="Latest in Synthetic Library 00"/>'
                    '<node focusable="true" focused="true" text="Synthetic Movie 000000"/></hierarchy>')
        self.assertTrue(cycle_state_on_home(home))
        home_without_focus = tree('<hierarchy><node text="Latest in Synthetic Library 00"/></hierarchy>')
        self.assertFalse(cycle_state_on_home(home_without_focus))


if __name__ == "__main__":
    unittest.main()
