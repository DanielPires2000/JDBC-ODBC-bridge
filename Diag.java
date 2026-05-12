import pt.daniel.odbc.OdbcDriver;
import java.util.Properties;
import java.sql.*;

public class Diag {
    public static void main(String[] args) throws Exception {
        String dsn = args.length > 0 ? args[0] : "Kerridge";
        OdbcDriver d = new OdbcDriver();
        Properties p = new Properties();
        p.setProperty("charset", "windows-1252");
        Connection c = d.connect("jdbc:odbc:" + dsn, p);
        if (c == null) { System.out.println("ERRO: sem ligacao"); return; }
        System.out.println("OK ligacao");

        DatabaseMetaData meta = c.getMetaData();

        // getColumns - listar tabelas primeiro
        ResultSet tabs = meta.getTables(null, null, null, null);
        String firstTable = null;
        int tc = 0;
        while (tabs.next() && tc < 3) {
            String tn = tabs.getString(3);
            System.out.println("Tabela: " + tn);
            if (firstTable == null) firstTable = tn;
            tc++;
        }
        tabs.close();

        if (firstTable != null) {
            System.out.println("\n--- getColumns para " + firstTable + " ---");
            ResultSet cols = meta.getColumns(null, null, firstTable, null);

            // Metadata DO resultado de getColumns
            ResultSetMetaData rm = cols.getMetaData();
            System.out.println("Nr colunas no resultado: " + rm.getColumnCount());
            for (int i = 1; i <= rm.getColumnCount(); i++) {
                System.out.println("  ResultCol " + i + ": name=" + rm.getColumnName(i)
                    + " jdbcType=" + rm.getColumnType(i) + " precision=" + rm.getPrecision(i));
            }

            // Dados (as colunas da tabela)
            System.out.println("\n--- Dados das colunas ---");
            int cc = 0;
            while (cols.next() && cc < 10) {
                String colName = cols.getString(4);
                String dataType = cols.getString(5);
                String typeName = cols.getString(6);
                String colSize = cols.getString(7);
                String decDig = cols.getString(9);
                System.out.println("  " + colName + " | type=" + dataType
                    + " | typeName=" + typeName + " | size=" + colSize + " | dec=" + decDig);
                cc++;
            }
            cols.close();

            // ResultSetMetaData de um SELECT
            System.out.println("\n--- SELECT * FROM " + firstTable + " (metadata) ---");
            Statement st = c.createStatement();
            try {
                ResultSet rs = st.executeQuery("SELECT * FROM " + firstTable);
                ResultSetMetaData qm = rs.getMetaData();
                for (int i = 1; i <= Math.min(qm.getColumnCount(), 10); i++) {
                    System.out.println("  " + qm.getColumnName(i) + " | type=" + qm.getColumnType(i)
                        + " | typeName=" + qm.getColumnTypeName(i) + " | precision=" + qm.getPrecision(i)
                        + " | scale=" + qm.getScale(i));
                }
                rs.close();
            } catch (Exception e) {
                System.out.println("  ERRO: " + e.getMessage());
            }
            st.close();
        }
        c.close();
        System.out.println("\nFIM");
    }
}
