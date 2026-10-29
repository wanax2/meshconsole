package meshconsole.ui;

import javax.swing.*;
import java.awt.*;

/**
 * A FlowLayout that reports the height it really needs when its components wrap onto several lines, so a row of
 * controls in a narrow window grows taller instead of being clipped (plain FlowLayout always asks for one line).
 */
public class WrapLayout extends FlowLayout {
    public WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }
    public WrapLayout(int align) { super(align); }

    @Override public Dimension preferredLayoutSize(Container target) { return layoutSize(target, true); }
    @Override public Dimension minimumLayoutSize(Container target) { Dimension d = layoutSize(target, false); d.width -= getHgap() + 1; return d; }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            Container c = target;
            while (c.getSize().width == 0 && c.getParent() != null) c = c.getParent();   // not laid out yet: use the nearest sized ancestor
            if (targetWidth == 0) targetWidth = c.getSize().width;
            if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;
            Insets in = target.getInsets();
            int maxWidth = targetWidth - (in.left + in.right + getHgap() * 2);
            Dimension dim = new Dimension(0, 0);
            int rowW = 0, rowH = 0;
            for (Component m : target.getComponents()) {
                if (!m.isVisible()) continue;
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (rowW + d.width > maxWidth && rowW > 0) { dim.width = Math.max(dim.width, rowW); dim.height += rowH + getVgap(); rowW = 0; rowH = 0; }
                if (rowW != 0) rowW += getHgap();
                rowW += d.width; rowH = Math.max(rowH, d.height);
            }
            dim.width = Math.max(dim.width, rowW); dim.height += rowH;
            dim.width += in.left + in.right + getHgap() * 2;
            dim.height += in.top + in.bottom + getVgap() * 2;
            // inside a scroll pane, don't ask to be wider than the viewport (that would stop the wrapping)
            Container sp = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (sp != null && target.isValid()) dim.width -= getHgap() + 1;
            return dim;
        }
    }
}
