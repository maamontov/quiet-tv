package local.quiet.tv;

import java.util.HashSet;
import java.util.Set;

public final class GridNavigationTest {
    private static void expect(int wanted, int actual) {
        if (wanted != actual) throw new AssertionError("Expected " + wanted + ", got " + actual);
    }
    public static void main(String[] args) {
        // An incomplete second row must remain reachable from any column above it.
        expect(7, GridNavigation.neighbor(4, 0, 1, 5, 8));
        expect(2, GridNavigation.neighbor(7, 0, -1, 5, 8));
        expect(4, GridNavigation.neighbor(4, 1, 0, 5, 8));
        expect(5, GridNavigation.neighbor(5, -1, 0, 5, 8));
        for (int columns = 1; columns <= 8; columns++) {
            for (int count = 1; count <= 64; count++) {
                Set<Integer> reached = new HashSet<>();
                reached.add(0);
                boolean changed;
                do {
                    changed = false;
                    for (int index : new HashSet<>(reached)) {
                        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                        for (int[] direction : directions) {
                            int next = GridNavigation.neighbor(index, direction[0], direction[1], columns, count);
                            if (next < 0 || next >= count) throw new AssertionError("Focus escaped grid");
                            if (direction[0] != 0 && next / columns != index / columns)
                                throw new AssertionError("Horizontal navigation wrapped a row");
                            changed |= reached.add(next);
                        }
                    }
                } while (changed);
                if (reached.size() != count) throw new AssertionError("Unreachable tiles: " + count + "/" + columns);
            }
        }
        System.out.println("Navigation passed: 512 grid shapes, valid boundaries and complete reachability");
    }
}
