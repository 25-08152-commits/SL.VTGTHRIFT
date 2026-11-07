import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.print.*;
import java.io.*;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class ThriftInventorySystem extends JFrame {

    private static final String STORE_NAME = "SL.VTGTHRIFT";
    private static final String APP_TITLE = "SL.VTGTHRIFT: Intelligent Garment Inventory & Point-of-Sale System";
    private static final String INVENTORY_FILE = "inventory_data.csv";
    private static final String SALES_FILE = "sales_history.csv";
    private static final List<String> VALID_CATEGORIES = Arrays.asList(
            "Shirts", "Shorts", "Pants", "Jackets", "Dresses", "Accessories"
    );
    private static final int LOW_STOCK_THRESHOLD = 3;

    private static final DecimalFormat MONEY_FORMAT = new DecimalFormat("#,##0.00");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static class Item {
        String id;
        String name;
        String category;
        int quantity;
        double price;

        Item(String id, String name, String category, int quantity, double price) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.quantity = quantity;
            this.price = price;
        }

        String toCsv() {
            return id + "," + escapeCsv(name) + "," + escapeCsv(category) + "," + quantity + "," + price;
        }

        static Item fromCsv(String line) {
            if (line == null || line.trim().isEmpty()) return null;

            String[] parts = splitCsv(line);
            if (parts.length < 5) return null;

            try {
                String id = parts[0];
                String name = parts[1];
                String category = parts[2];
                int qty = Integer.parseInt(parts[3]);
                double price = Double.parseDouble(parts[4]);

                return new Item(id, name, category, qty, price);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String[] splitCsv(String line) {
            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;

            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);

                if (ch == '"') {
                    if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = !inQuotes;
                    }
                } else if (ch == ',' && !inQuotes) {
                    result.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }

            result.add(current.toString());
            return result.toArray(new String[0]);
        }

        private static String escapeCsv(String value) {
            if (value == null) return "";
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }
    }

    static class CartItem {
        String id;
        String name;
        int quantity;
        double unitPrice;

        CartItem(String id, String name, int quantity, double unitPrice) {
            this.id = id;
            this.name = name;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }

        double getSubtotal() {
            return quantity * unitPrice;
        }
    }

    static class MultiItemReceipt {
        String transactionId;
        List<CartItem> items;
        double grandTotal;
        String date;

        MultiItemReceipt(String transactionId, List<CartItem> items, double grandTotal) {
            this.transactionId = transactionId;
            this.items = new ArrayList<>(items);
            this.grandTotal = grandTotal;
            this.date = DATE_FORMAT.format(new Date());
        }
    }

    static class ReceiptPrinter implements Printable {
        private final MultiItemReceipt receipt;

        ReceiptPrinter(MultiItemReceipt receipt) {
            this.receipt = receipt;
        }

        @Override
        public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) {
            if (pageIndex > 0) return NO_SUCH_PAGE;

            Graphics2D g2d = (Graphics2D) graphics;
            g2d.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
            g2d.setFont(new Font("Monospaced", Font.PLAIN, 9));

            int y = 20;
            g2d.drawString("==========================================", 10, y); y += 15;
            g2d.drawString("        " + STORE_NAME + " RECEIPT        ", 10, y); y += 15;
            g2d.drawString("==========================================", 10, y); y += 15;
            g2d.drawString("Tx ID: " + receipt.transactionId, 10, y); y += 12;
            g2d.drawString("Date:  " + receipt.date, 10, y); y += 18;

            g2d.drawString(String.format("%-18s %-4s %-8s %-8s", "ITEM", "QTY", "PRICE", "TOTAL"), 10, y);
            y += 12;
            g2d.drawString("------------------------------------------", 10, y); y += 15;

            for (CartItem item : receipt.items) {
                String line = String.format("%-18s %-4d %-8s %-8s",
                        truncate(item.name, 18),
                        item.quantity,
                        "P" + MONEY_FORMAT.format(item.unitPrice),
                        "P" + MONEY_FORMAT.format(item.getSubtotal())
                );
                g2d.drawString(line, 10, y);
                y += 15;
            }

            g2d.drawString("------------------------------------------", 10, y); y += 15;
            g2d.setFont(new Font("Monospaced", Font.BOLD, 10));
            g2d.drawString(String.format("GRAND TOTAL: P%s", MONEY_FORMAT.format(receipt.grandTotal)), 10, y);
            y += 20;

            g2d.setFont(new Font("Monospaced", Font.PLAIN, 9));
            g2d.drawString("==========================================", 10, y); y += 15;
            g2d.drawString("     Thank you for shopping at " + STORE_NAME + "!    ", 10, y);

            return PAGE_EXISTS;
        }

        private String truncate(String text, int maxLen) {
            if (text == null) return "";
            if (text.length() <= maxLen) return text;
            return text.substring(0, Math.max(0, maxLen - 3)) + "...";
        }
    }

    private final Map<String, Item> inventory = new LinkedHashMap<>();
    private final List<CartItem> shoppingCart = new ArrayList<>();
    private final List<MultiItemReceipt> salesHistory = new ArrayList<>();

    private DefaultTableModel inventoryTableModel;
    private DefaultTableModel cartTableModel;
    private DefaultTableModel salesTableModel;
    private JTable inventoryTable;
    private JComboBox<String> posItemCombo;
    private JLabel lblCartTotal;
    private JLabel lblTotalUnits, lblTotalValuation, lblLowStock;

    public ThriftInventorySystem() {
        setTitle(APP_TITLE);
        setSize(980, 680);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        loadData();
        loadSalesHistory();

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(24, 43, 73));
        JLabel titleLabel = new JLabel("  " + APP_TITLE, SwingConstants.LEFT);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setPreferredSize(new Dimension(800, 50));
        headerPanel.add(titleLabel, BorderLayout.CENTER);

        add(headerPanel, BorderLayout.NORTH);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("SansSerif", Font.PLAIN, 14));
        tabbedPane.addTab("Inventory Dashboard", createInventoryPanel());
        tabbedPane.addTab("Multi-Item POS System", createMultiItemPOSPanel());
        tabbedPane.addTab("Sales Reports", createSalesReportPanel());

        add(tabbedPane, BorderLayout.CENTER);

        refreshAllTables();
    }

    private JPanel createInventoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel statsPanel = new JPanel(new GridLayout(1, 3, 15, 0));
        lblTotalUnits = createCardLabel("Total Units: 0");
        lblTotalValuation = createCardLabel("Valuation: P0.00");
        lblLowStock = createCardLabel("Low Stock Alerts: 0");
        lblLowStock.setForeground(new Color(180, 40, 40));

        statsPanel.add(lblTotalUnits);
        statsPanel.add(lblTotalValuation);
        statsPanel.add(lblLowStock);

        panel.add(statsPanel, BorderLayout.NORTH);

        String[] columns = {"CATEGORY", "ITEM ID", "NAME", "QTY", "PRICE (P)"};
        inventoryTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        inventoryTable = new JTable(inventoryTableModel);
        inventoryTable.setRowHeight(25);
        panel.add(new JScrollPane(inventoryTable), BorderLayout.CENTER);

        JPanel formPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JTextField txtId = new JTextField(7);
        JTextField txtName = new JTextField(14);
        JComboBox<String> comboCat = new JComboBox<>(VALID_CATEGORIES.toArray(new String[0]));
        JTextField txtQty = new JTextField(5);
        JTextField txtPrice = new JTextField(7);
        JButton btnAdd = new JButton("Add Item");

        formPanel.add(new JLabel("ID:")); formPanel.add(txtId);
        formPanel.add(new JLabel("Name:")); formPanel.add(txtName);
        formPanel.add(new JLabel("Cat:")); formPanel.add(comboCat);
        formPanel.add(new JLabel("Qty:")); formPanel.add(txtQty);
        formPanel.add(new JLabel("Price:")); formPanel.add(txtPrice);
        formPanel.add(btnAdd);

        btnAdd.addActionListener(e -> {
            String id = txtId.getText().trim();
            String name = txtName.getText().trim();
            String cat = (String) comboCat.getSelectedItem();

            if (id.isEmpty() || name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "ID and Name cannot be empty.");
                return;
            }

            if (inventory.containsKey(id)) {
                JOptionPane.showMessageDialog(this, "Item ID already exists!");
                return;
            }

            try {
                int qty = Integer.parseInt(txtQty.getText().trim());
                double price = Double.parseDouble(txtPrice.getText().trim());

                if (qty < 0) {
                    JOptionPane.showMessageDialog(this, "Quantity cannot be negative.");
                    return;
                }

                if (price < 0) {
                    JOptionPane.showMessageDialog(this, "Price cannot be negative.");
                    return;
                }

                inventory.put(id, new Item(id, name, cat, qty, price));
                saveData();
                refreshAllTables();

                txtId.setText("");
                txtName.setText("");
                txtQty.setText("");
                txtPrice.setText("");
                comboCat.setSelectedIndex(0);

                JOptionPane.showMessageDialog(this, "Item added successfully!");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid quantity or price.");
            }
        });

        panel.add(formPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createMultiItemPOSPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel addPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        posItemCombo = new JComboBox<>();
        JTextField txtQty = new JTextField(5);
        JButton btnAddToCart = new JButton("Add to Cart");

        addPanel.add(new JLabel("Select Item:"));
        addPanel.add(posItemCombo);
        addPanel.add(new JLabel("Qty:"));
        addPanel.add(txtQty);
        addPanel.add(btnAddToCart);

        mainPanel.add(addPanel, BorderLayout.NORTH);

        String[] columns = {"ITEM ID", "NAME", "QTY", "UNIT PRICE (P)", "SUBTOTAL (P)"};
        cartTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JTable cartTable = new JTable(cartTableModel);
        cartTable.setRowHeight(25);
        mainPanel.add(new JScrollPane(cartTable), BorderLayout.CENTER);

        JPanel checkoutPanel = new JPanel(new BorderLayout());
        lblCartTotal = new JLabel("Cart Total: P0.00");
        lblCartTotal.setFont(new Font("SansSerif", Font.BOLD, 16));

        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnRemove = new JButton("Remove Selected");
        JButton btnCheckout = new JButton("Checkout & Print Receipt");
        btnCheckout.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnCheckout.setBackground(new Color(34, 139, 34));

        rightButtons.add(btnRemove);
        rightButtons.add(btnCheckout);

        checkoutPanel.add(lblCartTotal, BorderLayout.WEST);
        checkoutPanel.add(rightButtons, BorderLayout.EAST);
        mainPanel.add(checkoutPanel, BorderLayout.SOUTH);

        btnAddToCart.addActionListener(e -> {
            String selected = (String) posItemCombo.getSelectedItem();
            if (selected == null || selected.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please select an available item.");
                return;
            }

            String itemId = selected.split(" - ")[0];
            Item item = inventory.get(itemId);
            if (item == null) {
                JOptionPane.showMessageDialog(this, "Selected item not found.");
                return;
            }

            try {
                int qty = Integer.parseInt(txtQty.getText().trim());
                if (qty <= 0) {
                    JOptionPane.showMessageDialog(this, "Quantity must be greater than zero.");
                    return;
                }

                if (qty > item.quantity) {
                    JOptionPane.showMessageDialog(this, "Insufficient stock for this quantity.");
                    return;
                }

                item.quantity -= qty;

                // Merge duplicated items in cart
                boolean exists = false;
                for (CartItem cartItem : shoppingCart) {
                    if (cartItem.id.equals(itemId)) {
                        cartItem.quantity += qty;
                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    shoppingCart.add(new CartItem(itemId, item.name, qty, item.price));
                }

                txtQty.setText("");
                refreshAllTables();
                saveData();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Enter a valid integer quantity.");
            }
        });

        btnRemove.addActionListener(e -> {
            int selectedRow = cartTable.getSelectedRow();
            if (selectedRow < 0) {
                JOptionPane.showMessageDialog(this, "Select an item in the cart to remove.");
                return;
            }

            CartItem removed = shoppingCart.remove(selectedRow);
            if (inventory.containsKey(removed.id)) {
                inventory.get(removed.id).quantity += removed.quantity;
            }

            saveData();
            refreshAllTables();
        });

        btnCheckout.addActionListener(e -> {
            if (shoppingCart.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Your shopping cart is empty!");
                return;
            }

            double grandTotal = calculateCartTotal();
            int confirm = JOptionPane.showConfirmDialog(
                    this,
                    "Checkout " + shoppingCart.size() + " item type(s) for Total: P" + String.format("%.2f", grandTotal) + "?",
                    "Confirm Sale",
                    JOptionPane.YES_NO_OPTION
            );

            if (confirm == JOptionPane.YES_OPTION) {
                String txId = "TX" + System.currentTimeMillis() % 1000000;
                MultiItemReceipt receipt = new MultiItemReceipt(txId, shoppingCart, grandTotal);
                salesHistory.add(receipt);

                saveData();
                saveSalesHistory();

                shoppingCart.clear();
                refreshAllTables();

                int printConfirm = JOptionPane.showConfirmDialog(
                        this,
                        "Checkout complete! Print receipt now?",
                        "Print Receipt",
                        JOptionPane.YES_NO_OPTION
                );

                if (printConfirm == JOptionPane.YES_OPTION) {
                    PrinterJob job = PrinterJob.getPrinterJob();
                    job.setPrintable(new ReceiptPrinter(receipt));
                    if (job.printDialog()) {
                        try {
                            job.print();
                        } catch (PrinterException ex) {
                            JOptionPane.showMessageDialog(this, "Printing failed: " + ex.getMessage());
                        }
                    }
                }
            }
        });

        return mainPanel;
    }

    private JPanel createSalesReportPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        String[] columns = {"TX ID", "DATE", "ITEMS COUNT", "GRAND TOTAL (P)"};
        salesTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JTable table = new JTable(salesTableModel);
        table.setRowHeight(25);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        return panel;
    }

    private double calculateCartTotal() {
        double total = 0;
        for (CartItem ci : shoppingCart) {
            total += ci.quantity * ci.unitPrice;
        }
        return total;
    }

    private JLabel createCardLabel(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(new Font("SansSerif", Font.BOLD, 14));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        return label;
    }

    private void refreshAllTables() {
        inventoryTableModel.setRowCount(0);
        posItemCombo.removeAllItems();

        int totalUnits = 0;
        double totalValuation = 0;
        int lowStockCount = 0;

        for (Map.Entry<String, Item> entry : inventory.entrySet()) {
            Item item = entry.getValue();

            inventoryTableModel.addRow(new Object[]{
                    item.category,
                    entry.getKey(),
                    item.name,
                    item.quantity,
                    String.format("%.2f", item.price)
            });

            if (item.quantity > 0) {
                posItemCombo.addItem(entry.getKey() + " - " + item.name + " (Stock: " + item.quantity + ")");
            }

            totalUnits += item.quantity;
            totalValuation += item.quantity * item.price;

            if (item.quantity <= LOW_STOCK_THRESHOLD) {
                lowStockCount++;
            }
        }

        lblTotalUnits.setText("Total Units: " + totalUnits);
        lblTotalValuation.setText("Valuation: P" + String.format("%.2f", totalValuation));
        lblLowStock.setText("Low Stock Alerts: " + lowStockCount);

        cartTableModel.setRowCount(0);
        for (CartItem ci : shoppingCart) {
            cartTableModel.addRow(new Object[]{
                    ci.id,
                    ci.name,
                    ci.quantity,
                    String.format("%.2f", ci.unitPrice),
                    String.format("%.2f", ci.quantity * ci.unitPrice)
            });
        }

        lblCartTotal.setText("Cart Total: P" + String.format("%.2f", calculateCartTotal()));

        salesTableModel.setRowCount(0);
        double historyTotal = 0;
        for (MultiItemReceipt r : salesHistory) {
            salesTableModel.addRow(new Object[]{
                    r.transactionId,
                    r.date,
                    r.items.size(),
                    String.format("%.2f", r.grandTotal)
            });
            historyTotal += r.grandTotal;
        }

        if (!salesHistory.isEmpty()) {
            salesTableModel.addRow(new Object[]{"OVERALL TOTAL", "", "", String.format("%.2f", historyTotal)});
        }
    }

    private void saveData() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(INVENTORY_FILE))) {
            for (Map.Entry<String, Item> entry : inventory.entrySet()) {
                writer.println(entry.getValue().toCsv());
            }
        } catch (IOException e) {
            System.out.println("Error saving inventory data: " + e.getMessage());
        }
    }

    private void saveSalesHistory() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(SALES_FILE))) {
            for (MultiItemReceipt receipt : salesHistory) {
                writer.println(receipt.transactionId + "," + receipt.date + "," + receipt.grandTotal + "," + receipt.items.size());
            }
        } catch (IOException e) {
            System.out.println("Error saving sales history: " + e.getMessage());
        }
    }

    private void loadData() {
        File file = new File(INVENTORY_FILE);
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                Item item = Item.fromCsv(line);
                if (item != null) {
                    inventory.put(item.id, item);
                }
            }
        } catch (Exception e) {
            System.out.println("Error loading inventory file: " + e.getMessage());
        }
    }

    private void loadSalesHistory() {
        File file = new File(SALES_FILE);
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length < 4) continue;

                try {
                    String txId = parts[0];
                    String date = parts[1];
                    double total = Double.parseDouble(parts[2]);
                    int itemCount = Integer.parseInt(parts[3]);

                    MultiItemReceipt receipt = new MultiItemReceipt(txId, new ArrayList<>(), total);
                    receipt.date = date;
                    salesHistory.add(receipt);
                } catch (NumberFormatException ignored) {
                    // ignore invalid rows
                }
            }
        } catch (Exception e) {
            System.out.println("Error loading sales history: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ThriftInventorySystem app = new ThriftInventorySystem();
            app.setVisible(true);
        });
    }
}