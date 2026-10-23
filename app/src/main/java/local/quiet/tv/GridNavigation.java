package local.quiet.tv;

/** Geometry shared by remote navigation and tile rearrangement. */
final class GridNavigation {
    static int neighbor(int index, int dx, int dy, int columns, int count) {
        if (index < 0 || index >= count || columns < 1) return index;
        if (dy != 0) {
            int next = index + dy * columns;
            if (next < 0) return index;
            if (next >= count) {
                int lastRow = (count - 1) / columns;
                return index / columns < lastRow ? count - 1 : index;
            }
            return next;
        }
        int next = index + dx;
        return next >= 0 && next < count && index / columns == next / columns ? next : index;
    }
}
