package meshconsole.ui;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Generic editor for every radio setting: each Config / ModuleConfig section is rendered from its
 * protobuf descriptor, so all fields (including ones added by newer firmware) are editable.
 */
class ConfigEditorPanel extends JPanel {
    private final MeshClient client;
    private final MeshState state;
    private final JComboBox<String> section = new JComboBox<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private Message current;        // the section message being edited (e.g. Config.LoRaConfig)
    private boolean isModule;

    /** One editable row. path = "field" or "sub.field" for nested messages. */
    record Row(String path, Descriptors.FieldDescriptor fd, Message holder, String display, String type) { }

    private class Model extends AbstractTableModel {
        final String[] cols = {"Setting", "Value", "Type"};
        List<Row> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public boolean isCellEditable(int r, int c) { return c == 1; }
        @Override public Object getValueAt(int r, int c) { Row x = rows.get(r); return c == 0 ? x.path() : c == 1 ? x.display() : x.type(); }
        @Override public void setValueAt(Object v, int r, int c) { Row x = rows.get(r); rows.set(r, new Row(x.path(), x.fd(), x.holder(), String.valueOf(v), x.type())); fireTableCellUpdated(r, 1); }
    }

    ConfigEditorPanel(MeshClient client) {
        this.client = client;
        this.state = client.state();
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        top.add(new JLabel("Section:"));
        for (Config.PayloadVariantCase c : Config.PayloadVariantCase.values()) if (c != Config.PayloadVariantCase.PAYLOADVARIANT_NOT_SET) section.addItem("config." + c.name().toLowerCase());
        for (ModuleConfig.PayloadVariantCase c : ModuleConfig.PayloadVariantCase.values()) if (c != ModuleConfig.PayloadVariantCase.PAYLOADVARIANT_NOT_SET) section.addItem("module." + c.name().toLowerCase());
        section.addActionListener(e -> load());
        top.add(section);
        JButton reload = new JButton("Reload from radio"); reload.addActionListener(e -> load()); top.add(reload);
        JButton save = new JButton("Write to radio"); save.addActionListener(e -> save()); top.add(save);
        top.add(status);
        add(top, BorderLayout.NORTH);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setCellEditor(new ValueEditor());
        add(new JScrollPane(table), BorderLayout.CENTER);
        JTextArea note = new JTextArea("Every field of every section, straight from the firmware's protobuf definitions. Enums show their allowed values; lists are comma-separated; keys/bytes are base64. "
                + "'Write to radio' sends the whole section (unchanged fields are sent back as they were). Some sections make the radio reboot. Field meanings: https://meshtastic.org/docs/configuration/");
        note.setEditable(false); note.setLineWrap(true); note.setWrapStyleWord(true); note.setBackground(getBackground()); note.setFont(note.getFont().deriveFont(11f));
        add(note, BorderLayout.SOUTH);
        state.addListener(new MeshState.Listener() {
            @Override public void onConfigChanged() { SwingUtilities.invokeLater(() -> { if (model.rows.isEmpty()) load(); }); }
        });
    }

    private Message sectionMessage(String key) {
        if (key.startsWith("config.")) {
            Config c = state.config(Config.PayloadVariantCase.valueOf(key.substring(7).toUpperCase()));
            if (c == null) return null;
            Descriptors.FieldDescriptor fd = c.getDescriptorForType().findFieldByName(key.substring(7));
            return fd == null ? null : (Message) c.getField(fd);
        }
        ModuleConfig m = state.moduleConfig(ModuleConfig.PayloadVariantCase.valueOf(key.substring(7).toUpperCase()));
        if (m == null) return null;
        Descriptors.FieldDescriptor fd = m.getDescriptorForType().findFieldByName(key.substring(7));
        return fd == null ? null : (Message) m.getField(fd);
    }

    void load() {
        String key = (String) section.getSelectedItem();
        if (key == null) return;
        isModule = key.startsWith("module.");
        current = sectionMessage(key);
        model.rows = new ArrayList<>();
        if (current == null) { status.setText("Not received from the radio yet (connect, or the firmware does not have this section)."); model.fireTableDataChanged(); return; }
        addRows("", current);
        model.fireTableDataChanged();
        status.setText(model.rows.size() + " settings");
    }

    private void addRows(String prefix, Message m) {
        for (Descriptors.FieldDescriptor fd : m.getDescriptorForType().getFields()) {
            if (fd.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE && !fd.isRepeated()) { addRows(prefix + fd.getName() + ".", (Message) m.getField(fd)); continue; }
            Object v = m.getField(fd);
            String display, type;
            if (fd.isRepeated()) {
                List<?> l = (List<?>) v;
                StringBuilder sb = new StringBuilder();
                for (Object o : l) { if (sb.length() > 0) sb.append(", "); sb.append(fmt(fd, o)); }
                display = sb.toString(); type = "list of " + typeName(fd);
            } else { display = fmt(fd, v); type = typeName(fd); }
            model.rows.add(new Row(prefix + fd.getName(), fd, m, display, type));
        }
    }

    private static String fmt(Descriptors.FieldDescriptor fd, Object v) {
        return switch (fd.getJavaType()) {
            case ENUM -> ((Descriptors.EnumValueDescriptor) v).getName();
            case BYTE_STRING -> Base64.getEncoder().encodeToString(((ByteString) v).toByteArray());
            case INT -> fd.getType() == Descriptors.FieldDescriptor.Type.UINT32 || fd.getType() == Descriptors.FieldDescriptor.Type.FIXED32 ? Integer.toUnsignedString((Integer) v) : String.valueOf(v);
            default -> String.valueOf(v);
        };
    }

    private static String typeName(Descriptors.FieldDescriptor fd) {
        if (fd.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM) {
            StringBuilder sb = new StringBuilder();
            for (Descriptors.EnumValueDescriptor e : fd.getEnumType().getValues()) { if (sb.length() > 0) sb.append(" | "); sb.append(e.getName()); }
            return sb.toString();
        }
        return switch (fd.getJavaType()) { case BOOLEAN -> "true / false"; case BYTE_STRING -> "bytes (base64)"; case STRING -> "text"; case FLOAT, DOUBLE -> "number"; default -> fd.getType().name().toLowerCase(); };
    }

    private static Object parse(Descriptors.FieldDescriptor fd, String s) {
        s = s.trim();
        return switch (fd.getJavaType()) {
            case BOOLEAN -> s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on");
            case INT -> fd.getType() == Descriptors.FieldDescriptor.Type.UINT32 || fd.getType() == Descriptors.FieldDescriptor.Type.FIXED32 ? Integer.parseUnsignedInt(s) : Integer.parseInt(s);
            case LONG -> Long.parseLong(s);
            case FLOAT -> Float.parseFloat(s);
            case DOUBLE -> Double.parseDouble(s);
            case STRING -> s;
            case BYTE_STRING -> ByteString.copyFrom(s.isEmpty() ? new byte[0] : Base64.getDecoder().decode(s));
            case ENUM -> { Descriptors.EnumValueDescriptor e = fd.getEnumType().findValueByName(s.toUpperCase()); if (e == null) throw new IllegalArgumentException("no such value " + s); yield e; }
            default -> throw new IllegalArgumentException("unsupported");
        };
    }

    /** Rebuilds the section message from the table, then sends it. */
    private void save() {
        if (current == null) return;
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        try {
            Message.Builder b = current.toBuilder();
            for (Row r : model.rows) {
                Message.Builder target = b;
                String[] path = r.path().split("\\.");
                for (int i = 0; i < path.length - 1; i++) target = target.getFieldBuilder(target.getDescriptorForType().findFieldByName(path[i]));
                Descriptors.FieldDescriptor fd = target.getDescriptorForType().findFieldByName(path[path.length - 1]);
                if (r.fd().isRepeated()) {
                    target.clearField(fd);
                    for (String part : r.display().split(",")) if (!part.isBlank()) target.addRepeatedField(fd, parse(fd, part));
                } else {
                    Object v = parse(fd, r.display());
                    if (fd.hasPresence() && r.display().isBlank() && fd.getJavaType() != Descriptors.FieldDescriptor.JavaType.STRING) target.clearField(fd); else target.setField(fd, v);
                }
            }
            Message built = b.build();
            String key = (String) section.getSelectedItem();
            String field = key.substring(7);
            if (isModule) {
                ModuleConfig.Builder mc = ModuleConfig.newBuilder();
                mc.setField(mc.getDescriptorForType().findFieldByName(field), built);
                ModuleConfig mcb = mc.build();
                new Thread(() -> { try { client.setModuleConfig(mcb); SwingUtilities.invokeLater(() -> status.setText("Written " + key)); } catch (IOException ex) { SwingUtilities.invokeLater(() -> status.setText("Failed: " + ex.getMessage())); } }).start();
            } else {
                Config.Builder c = Config.newBuilder();
                c.setField(c.getDescriptorForType().findFieldByName(field), built);
                Config cb = c.build();
                new Thread(() -> { try { client.setConfig(cb); SwingUtilities.invokeLater(() -> status.setText("Written " + key + " (radio may reboot)")); } catch (IOException ex) { SwingUtilities.invokeLater(() -> status.setText("Failed: " + ex.getMessage())); } }).start();
            }
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Check the values: " + ex.getMessage(), "Cannot write", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Combo for enums/booleans, text otherwise. */
    private class ValueEditor extends AbstractCellEditor implements TableCellEditor {
        private JComponent editor;
        @Override public Object getCellEditorValue() {
            return editor instanceof JComboBox<?> cb ? String.valueOf(cb.getSelectedItem()) : ((JTextField) editor).getText();
        }
        @Override public Component getTableCellEditorComponent(JTable t, Object value, boolean sel, int row, int col) {
            Row r = model.rows.get(row);
            if (!r.fd().isRepeated() && r.fd().getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM) {
                JComboBox<String> cb = new JComboBox<>();
                for (Descriptors.EnumValueDescriptor e : r.fd().getEnumType().getValues()) cb.addItem(e.getName());
                cb.setSelectedItem(value); editor = cb;
            } else if (!r.fd().isRepeated() && r.fd().getJavaType() == Descriptors.FieldDescriptor.JavaType.BOOLEAN) {
                JComboBox<String> cb = new JComboBox<>(new String[]{"true", "false"}); cb.setSelectedItem(String.valueOf(value)); editor = cb;
            } else editor = new JTextField(String.valueOf(value));
            return editor;
        }
    }
}
