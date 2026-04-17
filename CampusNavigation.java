// ==============================
//  CAMPUS NAVIGATION SYSTEM  
// ==============================
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;

// ============================================================
//  ROOM  –  one room inside a hostel floor
// ============================================================
class Room {
    String number;   // e.g. "G01", "101"
    String type;     // "Single", "Double", "Triple"
    String occupant; // student name or "Vacant"
    Room(String number, String type, String occupant) {
        this.number = number; this.type = type; this.occupant = occupant;
    }
}

// ============================================================
//  FLOOR  –  one floor inside a hostel building
// ============================================================
class Floor {
    String name;
    Room[] rooms;
    Floor(String name, Room[] rooms) { this.name = name; this.rooms = rooms; }
}

// ============================================================
//  WAYPOINT  –  a road-junction point used for routing
// ============================================================
class Waypoint {
    int id, x, y;
    String label;
    Waypoint(int id, String label, int x, int y) {
        this.id = id; this.label = label; this.x = x; this.y = y;
    }
}

// ============================================================
//  WAYPOINT GRAPH  –  adjacency list + BFS path-finder
// ============================================================
class WaypointGraph {
    Waypoint[] points;
    List<Integer>[] adj;

    @SuppressWarnings("unchecked")
    WaypointGraph(Waypoint[] points) {
        this.points = points;
        adj = new ArrayList[points.length];
        for (int i = 0; i < points.length; i++) adj[i] = new ArrayList<>();
    }

    void addEdge(int a, int b) { adj[a].add(b); adj[b].add(a); }

    int[] findPath(int startId, int endId) {
        int n = points.length;
        int[] prev = new int[n];
        Arrays.fill(prev, -1);
        boolean[] vis = new boolean[n];
        Queue<Integer> q = new LinkedList<>();
        q.add(startId); vis[startId] = true;
        while (!q.isEmpty()) {
            int cur = q.poll();
            if (cur == endId) break;
            for (int nb : adj[cur]) if (!vis[nb]) { vis[nb] = true; prev[nb] = cur; q.add(nb); }
        }
        if (prev[endId] == -1 && startId != endId) return new int[0];
        List<Integer> path = new ArrayList<>();
        for (int v = endId; v != -1; v = prev[v]) path.add(0, v);
        return path.stream().mapToInt(Integer::intValue).toArray();
    }
}

// ============================================================
//  BUILDING  –  stores info about one building on the map
// ============================================================
class Building {
    String  name;
    int     x, y, width, height;
    String  info;
    Floor[] floors;
    int     waypointId;

    public Building(String name, int x, int y, int w, int h, String info) {
        this.name = name; this.x = x; this.y = y; this.width = w;
        this.height = h; this.info = info; this.floors = null; this.waypointId = -1;
    }
    public Building(String name, int x, int y, int w, int h, String info, Floor[] floors) {
        this(name, x, y, w, h, info); this.floors = floors;
    }
    public boolean isHostel()              { return floors != null; }
    public boolean isClicked(int px, int py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }
    public int centreX() { return x + width  / 2; }
    public int centreY() { return y + height / 2; }
    public double distanceTo(Building o) {
        int dx = centreX()-o.centreX(), dy = centreY()-o.centreY();
        return Math.sqrt(dx*dx + dy*dy);
    }
    @Override public String toString() { return name; }
}

// ============================================================
//  CAMPUS PATH  –  draws visual roads on the map
// ============================================================
class CampusPath {
    int x, y, width, height; boolean isCircular;
    public CampusPath(int x, int y, int w, int h, boolean circ) {
        this.x=x; this.y=y; this.width=w; this.height=h; this.isCircular=circ;
    }
}

// ============================================================
//  MAP PANEL  –  the drawing canvas
// ============================================================
class MapPanel extends JPanel {

    Building[]   buildings;
    CampusPath[] paths;
    WaypointGraph graph;
    Building[]   classroomList;

    Building start = null, end = null, hoveredBuilding = null;

    // Drill-down state
    Building clickedBuilding = null;
    Floor    selectedFloor   = null;
    Room     selectedRoom    = null;
    boolean  showClassList   = false;

    // Hit-test rectangles rebuilt every repaint
    Rectangle[] floorHitBoxes = null;
    Rectangle[] roomHitBoxes  = null;
    Rectangle   navBtnRect    = null;
    Rectangle[] classHitBoxes = null;

    // Navigation path (Hostel room -> Classroom)
    int[]    navPath   = null;
    Building navSource = null;
    Building navTarget = null;
    double   navDistance = 0;
    
    // Route path (Building -> Building from right panel)
    int[] routePath = null;

    BufferedImage backgroundImage = null;
    
    // ── Auto-dismiss timer (4 seconds of no interaction → card disappears) ──
    Timer dismissTimer;

    public MapPanel(Building[] buildings, CampusPath[] paths,
                    WaypointGraph graph, Building[] classroomList) {
        this.buildings     = buildings;
        this.paths         = paths;
        this.graph         = graph;
        this.classroomList = classroomList;
        setBackground(new Color(15, 20, 40));

        // Timer fires once after 4 s — hides the info card but NOT the nav path
        dismissTimer = new Timer(4000, e -> {
            clickedBuilding = null; selectedFloor = null; selectedRoom = null;
            showClassList = false;
            // navPath & navTarget are intentionally kept so the route stays visible
            repaint();
        });
        dismissTimer.setRepeats(false);

        File img = new File("campus_map.png");
        if (img.exists()) {
            try { backgroundImage = ImageIO.read(img); System.out.println("Image loaded."); }
            catch (Exception e) { System.out.println("Image error: " + e.getMessage()); }
        } else { System.out.println("campus_map.png not found."); }

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                Building found = null;
                for (Building b : buildings) if (b.isClicked(e.getX(), e.getY())) { found = b; break; }
                if (hoveredBuilding != found) { hoveredBuilding = found; repaint(); }
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int mx = e.getX(), my = e.getY();
                System.out.println("Click x:" + mx + " y:" + my);

                // 1. Classroom list click
                if (showClassList && classHitBoxes != null) {
                    for (int i = 0; i < classHitBoxes.length; i++) {
                        if (classHitBoxes[i] != null && classHitBoxes[i].contains(mx, my)) {
                            navigateTo(classroomList[i]);
                            // Path is now set — dismiss the card immediately and stop timer
                            // so the route stays on screen until user manually clears it
                            clickedBuilding = null; selectedFloor = null; selectedRoom = null;
                            showClassList = false; dismissTimer.stop();
                            repaint(); return;
                        }
                    }
                    showClassList = false; repaint(); return;
                }
                // 2. Navigate button
                if (navBtnRect != null && navBtnRect.contains(mx, my)) {
                    showClassList = true; resetDismissTimer(); repaint(); return;
                }
                // 3. Floor row click
                if (floorHitBoxes != null && clickedBuilding != null
                        && clickedBuilding.isHostel() && selectedFloor == null) {
                    for (int i = 0; i < floorHitBoxes.length; i++) {
                        if (floorHitBoxes[i] != null && floorHitBoxes[i].contains(mx, my)) {
                            selectedFloor = clickedBuilding.floors[i];
                            selectedRoom = null; navPath = null;
                            resetDismissTimer(); repaint(); return;
                        }
                    }
                }
                // 4. Room cell click
                if (roomHitBoxes != null && selectedFloor != null && selectedRoom == null) {
                    for (int i = 0; i < roomHitBoxes.length; i++) {
                        if (roomHitBoxes[i] != null && roomHitBoxes[i].contains(mx, my)) {
                            selectedRoom = selectedFloor.rooms[i];
                            navPath = null; navTarget = null;
                            resetDismissTimer(); repaint(); return;
                        }
                    }
                }
                // 5. Building click on map
                for (Building b : buildings) {
                    if (b.isClicked(mx, my)) {
                        clickedBuilding = b; selectedFloor = null;
                        selectedRoom = null; navPath = null;
                        navTarget = null; showClassList = false;
                        resetDismissTimer(); repaint(); return;
                    }
                }
                // 6. Empty space — dismiss immediately and stop timer
                dismissTimer.stop();
                clickedBuilding = null; selectedFloor = null; selectedRoom = null;
                navPath = null; navSource = null; navTarget = null; showClassList = false; repaint();
            }
        });
    }

    // Restart the 4-second auto-dismiss countdown
    void resetDismissTimer() { dismissTimer.restart(); }

    void navigateTo(Building target) {
        if (clickedBuilding == null || target == null) return;
        int from = clickedBuilding.waypointId, to = target.waypointId;
        navPath   = (from < 0 || to < 0) ? new int[0] : graph.findPath(from, to);
        navSource = clickedBuilding;
        navTarget = target;
        
        navDistance = 0;
        if (navPath != null && navPath.length > 0) {
            Waypoint firstWP = graph.points[navPath[0]];
            navDistance += Math.hypot(navSource.centreX() - firstWP.x, navSource.centreY() - firstWP.y);
            for (int i = 0; i < navPath.length - 1; i++) {
                Waypoint w1 = graph.points[navPath[i]];
                Waypoint w2 = graph.points[navPath[i+1]];
                navDistance += Math.hypot(w1.x - w2.x, w1.y - w2.y);
            }
            Waypoint lastWP = graph.points[navPath[navPath.length - 1]];
            navDistance += Math.hypot(navTarget.centreX() - lastWP.x, navTarget.centreY() - lastWP.y);
        } else {
            navDistance = navSource.distanceTo(navTarget);
        }
        navDistance *= 2.0;
        
        if (CampusNavigation.statusLabel != null && CampusNavigation.infoArea != null) {
            String fromStr = navSource.name + (selectedRoom != null ? " (Room " + selectedRoom.number + ")" : "");
            CampusNavigation.statusLabel.setText("  Route: " + fromStr + " → " + navTarget.name +
                    "  |  ~" + String.format("%.0f", navDistance) + " m");
            CampusNavigation.infoArea.setText("=== HOSTEL ROUTE ===\nFROM: " + fromStr +
                    "\nTO:   " + navTarget.name +
                    "\n\nDist: ~" + String.format("%.0f", navDistance) + " metres via roads\n\nClick Reset to clear map.");
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // Reset hit-boxes each repaint
        floorHitBoxes = null; roomHitBoxes = null; navBtnRect = null; classHitBoxes = null;

        // Anti-aliasing
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background gradient
        if (backgroundImage != null) g.drawImage(backgroundImage, 250, 10, 500, 700, this);
        else {
            java.awt.GradientPaint bgGrad = new java.awt.GradientPaint(0,0,new Color(10,15,35),getWidth(),getHeight(),new Color(20,35,70));
            g2d.setPaint(bgGrad); g2d.fillRect(0,0,getWidth(),getHeight());
        }

        // Road paths — warm asphalt tone with subtle glow
        if (paths != null) {
            for (CampusPath p : paths) {
                g.setColor(new Color(45, 55, 80));
                if (p.isCircular) g.fillOval(p.x, p.y, p.width, p.height);
                else              g.fillRect(p.x, p.y, p.width, p.height);
                g.setColor(new Color(70, 85, 120, 120));
                if (p.isCircular) ((Graphics2D)g).drawOval(p.x, p.y, p.width, p.height);
                else              ((Graphics2D)g).drawRect(p.x, p.y, p.width, p.height);
            }
        }

        // Buildings — color-coded by category with rounded corners and glow border
        for (Building b : buildings) {
            Color fill, border;
            String nm = b.name;
            if (b == start) { fill = new Color(30, 180, 80); border = new Color(100, 255, 150); }
            else if (b == end) { fill = new Color(200, 40, 40); border = new Color(255, 110, 110); }
            else if (b.isHostel()) { fill = new Color(80, 50, 130); border = new Color(160, 110, 255); }
            else if (nm.contains("Block") && !nm.equals("K Block")) { fill = new Color(30, 80, 160); border = new Color(80, 150, 255); }
            else if (nm.contains("Gate") || nm.contains("Parking")) { fill = new Color(100, 80, 20); border = new Color(200, 170, 60); }
            else if (nm.contains("Tennis") || nm.contains("Pickleball") || nm.equals("K Block")
                    || nm.contains("Volleyball") || nm.contains("Basketball")
                    || nm.contains("Football") || nm.contains("Cricket") || nm.contains("Hanger") || nm.contains("Padel")) {
                fill = new Color(20, 90, 60); border = new Color(50, 200, 120);
            }
            else if (nm.contains("Mess") || nm.contains("Snapeats") || nm.contains("Hotspot") || nm.contains("Stories")) {
                fill = new Color(130, 60, 20); border = new Color(255, 150, 60);
            }
            else { fill = new Color(40, 70, 120); border = new Color(80, 130, 220); }
            g.setColor(fill);
            ((Graphics2D)g).fillRoundRect(b.x, b.y, b.width, b.height, 5, 5);
            g.setColor(border);
            ((Graphics2D)g).drawRoundRect(b.x, b.y, b.width, b.height, 5, 5);
        }

        // Hover name tooltip pill
        if (hoveredBuilding != null) {
            Building hb = hoveredBuilding;
            FontMetrics fm = g.getFontMetrics(new Font("SansSerif", Font.BOLD, 11));
            int tw = fm.stringWidth(hb.name), th = 14, px = 6, py = 3;
            int bx = hb.x + hb.width/2 - tw/2 - px, by = hb.y - th - py*2 - 2;
            g.setColor(new Color(0,0,0,170));
            ((Graphics2D)g).fillRoundRect(bx, by, tw+px*2, th+py*2, 8, 8);
            g.setColor(new Color(140, 200, 255));
            ((Graphics2D)g).drawRoundRect(bx, by, tw+px*2, th+py*2, 8, 8);
            g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.drawString(hb.name, bx+px, by+th+py-1);
        }

        // Route line (Building -> Building) — glowing cyan dashed
        if (start != null && end != null) {
            Graphics2D g2 = (Graphics2D) g;
            Stroke old = g2.getStroke();
            if (routePath != null && routePath.length > 1) {
                // Glow layer
                g2.setColor(new Color(0, 200, 255, 50));
                g2.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Waypoint fWP = graph.points[routePath[0]];
                g2.drawLine(start.centreX(), start.centreY(), fWP.x, fWP.y);
                for (int i = 0; i < routePath.length-1; i++) {
                    Waypoint a = graph.points[routePath[i]], b2 = graph.points[routePath[i+1]];
                    g2.drawLine(a.x, a.y, b2.x, b2.y);
                }
                Waypoint lWP = graph.points[routePath[routePath.length-1]];
                g2.drawLine(lWP.x, lWP.y, end.centreX(), end.centreY());
                // Main dashed line
                g2.setColor(new Color(0, 220, 255));
                g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{10,6}, 0));
                g2.drawLine(start.centreX(), start.centreY(), fWP.x, fWP.y);
                for (int i = 0; i < routePath.length-1; i++) {
                    Waypoint a = graph.points[routePath[i]], b2 = graph.points[routePath[i+1]];
                    g2.drawLine(a.x, a.y, b2.x, b2.y);
                }
                g2.drawLine(lWP.x, lWP.y, end.centreX(), end.centreY());
            } else {
                g2.setColor(new Color(0, 200, 255, 80));
                g2.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(start.centreX(), start.centreY(), end.centreX(), end.centreY());
                g2.setColor(new Color(0, 220, 255));
                g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{8,5}, 0));
                g2.drawLine(start.centreX(), start.centreY(), end.centreX(), end.centreY());
            }
            g2.setStroke(old);
            // Start marker (green pulse)
            g2.setColor(new Color(0,255,120,60)); g2.fillOval(start.centreX()-10, start.centreY()-10, 20, 20);
            g2.setColor(new Color(0,230,100));    g2.fillOval(start.centreX()-6,  start.centreY()-6,  12, 12);
            // End marker (red pulse)
            g2.setColor(new Color(255,60,60,60)); g2.fillOval(end.centreX()-10, end.centreY()-10, 20, 20);
            g2.setColor(new Color(255,80,80));    g2.fillOval(end.centreX()-6,  end.centreY()-6,  12, 12);
        }

        // Waypoint nav path (Hostel room -> Classroom, amber glow dashed)
        if (navPath != null && navPath.length > 1 && navSource != null && navTarget != null) {
            Graphics2D g2 = (Graphics2D) g;
            Stroke old = g2.getStroke();
            Waypoint firstWP = graph.points[navPath[0]];
            Waypoint lastWP  = graph.points[navPath[navPath.length-1]];
            // Glow
            g2.setColor(new Color(255, 200, 0, 50));
            g2.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(navSource.centreX(), navSource.centreY(), firstWP.x, firstWP.y);
            for (int i = 0; i < navPath.length-1; i++) {
                Waypoint a = graph.points[navPath[i]], b2 = graph.points[navPath[i+1]];
                g2.drawLine(a.x, a.y, b2.x, b2.y);
            }
            g2.drawLine(lastWP.x, lastWP.y, navTarget.centreX(), navTarget.centreY());
            // Main line
            g2.setColor(new Color(255, 215, 0));
            g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{8,5}, 0));
            g2.drawLine(navSource.centreX(), navSource.centreY(), firstWP.x, firstWP.y);
            for (int i = 0; i < navPath.length-1; i++) {
                Waypoint a = graph.points[navPath[i]], b2 = graph.points[navPath[i+1]];
                g2.drawLine(a.x, a.y, b2.x, b2.y);
            }
            g2.drawLine(lastWP.x, lastWP.y, navTarget.centreX(), navTarget.centreY());
            g2.setStroke(old);
            // Waypoint dots
            g2.setColor(new Color(255, 220, 80));
            for (int id : navPath) { Waypoint w = graph.points[id]; g2.fillOval(w.x-4, w.y-4, 8, 8); }
            // Target circle
            g2.setColor(new Color(255,80,80,80)); g2.fillOval(navTarget.centreX()-10, navTarget.centreY()-10, 20, 20);
            g2.setColor(new Color(220, 60, 60));  g2.fillOval(navTarget.centreX()-6, navTarget.centreY()-6, 12, 12);
        }

        // Legend — pill badges
        int ly = getHeight() - 28;
        drawLegendPill(g2d, new Color(30,180,80),  new Color(100,255,150), "● Start",   8,  ly);
        drawLegendPill(g2d, new Color(200,40,40),  new Color(255,110,110), "● End",     80, ly);
        drawLegendPill(g2d, new Color(0,200,255),  new Color(0,180,255),   "─ Route",  148, ly);
        drawLegendPill(g2d, new Color(180,140,0),  new Color(255,215,0),   "─ Nav",    218, ly);

        // Floating card (drawn last = on top)
        if (clickedBuilding != null) drawInfoCard(g);
        if (showClassList)           drawClassroomList(g);
    }

    // ── Which card to show ─────────────────────────────────────
    private void drawInfoCard(Graphics g) {
        if (clickedBuilding.isHostel()) {
            if      (selectedRoom  != null) drawRoomDetail(g);
            else if (selectedFloor != null) drawRoomGrid(g);
            else                            drawFloorList(g);
        } else {
            drawFloatingInfo(g);
        }
    }

    // ── Card A: plain info (non-hostel) ───────────────────────
    private void drawFloatingInfo(Graphics g) {
        Building b = clickedBuilding;
        int cW = 200, pad = 10, lH = 16;
        List<String> lines = wrapText(g, b.info, cW - pad*2);
        int cH = pad*2 + lH + 4 + lines.size()*lH;
        int cX = cardX(b, cW), cY = cardY(b, cH);
        drawCardBase(g, cX, cY, cW, cH);
        drawPointer(g, b, cX, cY, cW, cH);
        drawCardTitle(g, b.name, cX, cY, cW, pad);
        g.setColor(new Color(210, 225, 255)); g.setFont(new Font("Arial", Font.PLAIN, 11));
        int ty = cY + pad + 20 + lH - 2;
        for (String line : lines) { g.drawString(line, cX+pad, ty); ty += lH; }
    }

    // ── Card B: floor list ────────────────────────────────────
    private void drawFloorList(Graphics g) {
        Building b = clickedBuilding;
        int cW = 210, pad = 10, rowH = 26;
        int cH = pad*2 + 22 + b.floors.length * (rowH+3) + 4;
        int cX = cardX(b, cW), cY = cardY(b, cH);
        drawCardBase(g, cX, cY, cW, cH);
        drawPointer(g, b, cX, cY, cW, cH);
        drawCardTitle(g, b.name + " — Select Floor", cX, cY, cW, pad);
        floorHitBoxes = new Rectangle[b.floors.length];
        int fy = cY + pad + 24;
        for (int i = 0; i < b.floors.length; i++) {
            Rectangle r = new Rectangle(cX+pad, fy, cW-pad*2, rowH);
            floorHitBoxes[i] = r;
            g.setColor(new Color(40, 60, 130));       g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
            g.setColor(new Color(100, 160, 255));     g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
            g.setColor(new Color(210, 230, 255)); g.setFont(new Font("Arial", Font.BOLD, 11));
            g.drawString("▶ " + b.floors[i].name + "  [" + b.floors[i].rooms.length + " rooms]",
                    r.x+6, r.y+rowH/2+4);
            fy += rowH + 3;
        }
    }

    // ── Card C: room grid ─────────────────────────────────────
    private void drawRoomGrid(Graphics g) {
        Building b  = clickedBuilding;
        Room[]   rm = selectedFloor.rooms;
        int cW = 220, pad = 8, cols = 4, cellW = 48, cellH = 26;
        int rows = (rm.length + cols - 1) / cols;
        int cH = pad*2 + 36 + rows * (cellH + 3) + 8;
        int cX = cardX(b, cW), cY = cardY(b, cH);
        drawCardBase(g, cX, cY, cW, cH);
        drawPointer(g, b, cX, cY, cW, cH);
        drawCardTitle(g, b.name + " – " + selectedFloor.name, cX, cY, cW, pad);
        g.setColor(new Color(150, 180, 220)); g.setFont(new Font("Arial", Font.PLAIN, 9));
        g.drawString("Click a room for details", cX+pad, cY+pad+26);
        roomHitBoxes = new Rectangle[rm.length];
        int startY = cY + pad + 34;
        for (int i = 0; i < rm.length; i++) {
            int col = i%cols, row = i/cols;
            int rx = cX + pad + col*(cellW+3), ry = startY + row*(cellH+3);
            Rectangle cell = new Rectangle(rx, ry, cellW, cellH);
            roomHitBoxes[i] = cell;
            boolean vacant = rm[i].occupant.equals("");
            g.setColor(vacant ? new Color(20, 80, 40) : new Color(60, 30, 30));
            g.fillRoundRect(rx, ry, cellW, cellH, 5, 5);
            g.setColor(vacant ? new Color(0, 220, 80) : new Color(255, 100, 100));
            g.drawRoundRect(rx, ry, cellW, cellH, 5, 5);
            g.setColor(Color.WHITE); g.setFont(new Font("Arial", Font.BOLD, 10));
            g.drawString(rm[i].number, rx+5, ry+cellH/2+4);
        }
    }

    // ── Card D: room detail + nav button ─────────────────────
    private void drawRoomDetail(Graphics g) {
        Building b = clickedBuilding; Room r = selectedRoom;
        int cW = 210, pad = 10;
        int cH = pad*2 + 20 + 16*4 + 30 + 14;
        int cX = cardX(b, cW), cY = cardY(b, cH);
        drawCardBase(g, cX, cY, cW, cH);
        drawPointer(g, b, cX, cY, cW, cH);
        drawCardTitle(g, b.name + " – Room " + r.number, cX, cY, cW, pad);
        g.setFont(new Font("Arial", Font.PLAIN, 11));
        int ty = cY + pad + 30;
        drawInfoLine(g, "Floor    : " + selectedFloor.name, cX+pad, ty); ty += 16;
        drawInfoLine(g, "Room No. : " + r.number,           cX+pad, ty); ty += 16;
        drawInfoLine(g, "Type     : " + r.type,             cX+pad, ty); ty += 16;
        // Status line removed as per user request
        g.setColor(new Color(150, 180, 220)); g.setFont(new Font("Arial", Font.PLAIN, 9));
        g.drawString("(click different building to restart)", cX+pad, ty); ty += 14;
        // Navigate button
        int btnX=cX+pad, btnY=ty, btnW=cW-pad*2, btnH=22;
        navBtnRect = new Rectangle(btnX, btnY, btnW, btnH);
        g.setColor(new Color(30, 80, 180));  g.fillRoundRect(btnX, btnY, btnW, btnH, 8, 8);
        g.setColor(new Color(100, 160, 255)); g.drawRoundRect(btnX, btnY, btnW, btnH, 8, 8);
        g.setColor(Color.WHITE); g.setFont(new Font("Arial", Font.BOLD, 11));
        g.drawString("  → Navigate to Classroom", btnX+6, btnY+15);
        if (navTarget != null) {
            g.setColor(new Color(255, 220, 80)); g.setFont(new Font("Arial", Font.BOLD, 10));
            String distStr = (navPath!=null && navPath.length>0) ? " ✓ (~" + String.format("%.0f", navDistance) + "m)" : " (no path)";
            g.drawString("Route to: " + navTarget.name + distStr,
                    cX+pad, btnY+btnH+12);
        }
    }

    // ── Classroom selection overlay ───────────────────────────
    private void drawClassroomList(Graphics g) {
        int cW=200, pad=8, rowH=22;
        int cH = pad*2 + 18 + classroomList.length*(rowH+2);
        int cX = getWidth()/2-cW/2, cY = getHeight()/2-cH/2;
        g.setColor(new Color(0, 0, 0, 120)); g.fillRect(0, 0, getWidth(), getHeight());
        drawCardBase(g, cX, cY, cW, cH);
        g.setColor(Color.WHITE); g.setFont(new Font("Arial", Font.BOLD, 12));
        g.drawString("Select Classroom", cX+pad, cY+pad+12);
        g.setColor(new Color(100,160,255,180));
        g.drawLine(cX+pad, cY+pad+16, cX+cW-pad, cY+pad+16);
        classHitBoxes = new Rectangle[classroomList.length];
        int fy = cY+pad+22;
        for (int i = 0; i < classroomList.length; i++) {
            Rectangle r = new Rectangle(cX+pad, fy, cW-pad*2, rowH);
            classHitBoxes[i] = r;
            g.setColor(new Color(40, 60, 130)); g.fillRoundRect(r.x, r.y, r.width, r.height, 5, 5);
            g.setColor(new Color(100, 160, 255)); g.drawRoundRect(r.x, r.y, r.width, r.height, 5, 5);
            g.setColor(new Color(200, 220, 255)); g.setFont(new Font("Arial", Font.PLAIN, 11));
            g.drawString(classroomList[i].name, r.x+6, r.y+rowH/2+4);
            fy += rowH+2;
        }
    }

    // ── Shared helpers ─────────────────────────────────────────
    private int cardX(Building b, int cW) {
        int cx = b.x+b.width+8; return (cx+cW > getWidth()) ? b.x-cW-8 : cx;
    }
    private int cardY(Building b, int cH) {
        int cy = b.centreY()-cH/2;
        if (cy < 5) cy = 5;
        if (cy+cH > getHeight()-5) cy = getHeight()-cH-5;
        return cy;
    }
    private void drawCardBase(Graphics g, int x, int y, int w, int h) {
        // Shadow
        g.setColor(new Color(0,0,0,90));  ((Graphics2D)g).fillRoundRect(x+4,y+4,w,h,14,14);
        // Glassmorphism body
        g.setColor(new Color(12,20,50,235)); ((Graphics2D)g).fillRoundRect(x,y,w,h,14,14);
        // Gradient top stripe
        java.awt.GradientPaint gp = new java.awt.GradientPaint(x,y,new Color(60,100,200,120),x,y+24,new Color(10,20,60,0));
        ((Graphics2D)g).setPaint(gp); ((Graphics2D)g).fillRoundRect(x,y,w,24,14,14);
        // Border
        g.setColor(new Color(80,140,255,200)); ((Graphics2D)g).drawRoundRect(x,y,w,h,14,14);
    }
    private void drawCardTitle(Graphics g, String title, int cx, int cy, int cw, int pad) {
        g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString(title.length()>28 ? title.substring(0,27)+"…" : title, cx+pad, cy+pad+12);
        g.setColor(new Color(80,140,255,160));
        ((Graphics2D)g).drawLine(cx+pad, cy+pad+17, cx+cw-pad, cy+pad+17);
    }
    private void drawPointer(Graphics g, Building b, int cX, int cY, int cW, int cH) {
        int triX; int[] xs, ys;
        if (cX > b.x) { triX=cX; xs=new int[]{triX,triX-8,triX}; }
        else           { triX=cX+cW; xs=new int[]{triX,triX+8,triX}; }
        int ty2 = Math.max(cY+14, Math.min(b.centreY(), cY+cH-14));
        ys = new int[]{ty2-7, ty2, ty2+7};
        g.setColor(new Color(20,30,60,230)); g.fillPolygon(xs,ys,3);
        g.setColor(new Color(100,160,255));  g.drawPolygon(xs,ys,3);
    }
    private void drawInfoLine(Graphics g, String text, int x, int y) {
        g.setColor(new Color(200, 220, 255)); g.drawString(text, x, y);
    }
    private List<String> wrapText(Graphics g, String text, int maxW) {
        FontMetrics fm = g.getFontMetrics(new Font("Arial", Font.PLAIN, 11));
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split(" ")) {
            String test = cur.length()==0 ? w : cur+" "+w;
            if (fm.stringWidth(test)>maxW) { if(cur.length()>0) lines.add(cur.toString()); cur=new StringBuilder(w); }
            else cur=new StringBuilder(test);
        }
        if (cur.length()>0) lines.add(cur.toString());
        return lines;
    }
    private void drawLegendBox(Graphics g, Color c, String label, int x, int y) {
        g.setColor(c); g.fillRect(x,y,14,14); g.setColor(Color.BLACK); g.drawRect(x,y,14,14);
        g.setFont(new Font("SansSerif", Font.PLAIN, 9)); g.drawString(label, x+17, y+11);
    }
    private void drawLegendPill(Graphics2D g2, Color fill, Color text, String label, int x, int y) {
        FontMetrics fm = g2.getFontMetrics(new Font("SansSerif", Font.BOLD, 10));
        int tw = fm.stringWidth(label), ph = 18, pw = tw+16;
        g2.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 40));
        g2.fillRoundRect(x, y, pw, ph, ph, ph);
        g2.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 160));
        g2.drawRoundRect(x, y, pw, ph, ph, ph);
        g2.setColor(text); g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.drawString(label, x+8, y+13);
    }
}

// ============================================================
//  MAIN CLASS
// ============================================================
public class CampusNavigation {

    // ── Build one floor's worth of rooms ──────────────────────
    static Floor makeFloor(String name, String prefix, int count, String[] types, String[] names) {
        Room[] rooms = new Room[count];
        for (int i = 0; i < count; i++) {
            String num  = prefix + String.format("%02d", i+1);
            String type = types[i % types.length];
            String occ  = (names != null && i < names.length) ? names[i] : "";
            rooms[i] = new Room(num, type, occ);
        }
        return new Floor(name, rooms);
    }

    // ── Boys Hostel floors ────────────────────────────────────
    static final Floor[] boysFloors = {
        makeFloor("Ground Floor","G",15,new String[]{"Triple"}, new String[0]),
        makeFloor("Floor 1","1",15,new String[]{"Triple"}, new String[0]),
        makeFloor("Floor 2","2",15,new String[]{"Triple"}, new String[0]),
        makeFloor("Floor 3","3",15,new String[]{"Triple"}, new String[0]),
    };

    // ── Girls Hostel floors ───────────────────────────────────
    static final Floor[] girlsFloors = {
        makeFloor("Ground Floor","G",0,new String[0], new String[0]),
        makeFloor("Floor 1","1",10,new String[]{"Triple"}, new String[0]),
        makeFloor("Floor 2","2",10,new String[]{"Triple"}, new String[0]),
        makeFloor("Floor 3","3",10,new String[]{"Triple"}, new String[0]),
    };

    // ── Waypoints ─────────────────────────────────────────────
    static final Waypoint[] waypoints = {
        new Waypoint(0,  "NW-Top",      265,  22),
        new Waypoint(1,  "Center-Top",  437,  22),
        new Waypoint(2,  "East-Top",    584,  22),
        new Waypoint(3,  "K-Top",       736,  22),
        new Waypoint(4,  "Mess-Top",    437,  98),
        new Waypoint(5,  "E-Mess-Top",  584,  98),
        new Waypoint(6,  "West-Mid",    265, 179),
        new Waypoint(7,  "Center-Mid",  437, 179),
        new Waypoint(8,  "East-Mid",    584, 179),
        new Waypoint(9,  "West-Low",    265, 326),
        new Waypoint(10, "Center-AB",   437, 326),
        new Waypoint(11, "E-K-Jct",     584, 292),
        new Waypoint(12, "East-AB",     584, 326),
        new Waypoint(13, "K-Jct",       736, 292),
        new Waypoint(14, "Center-PN",   437, 474),
        new Waypoint(15, "P-Jct",       382, 474),
        new Waypoint(16, "N-Jct",       492, 474),
        new Waypoint(17, "P-Bot",       382, 615),
        new Waypoint(18, "N-Bot",       492, 615),
        new Waypoint(19, "West-Gate",   265, 686),
        new Waypoint(20, "East-Gate",   584, 686),
    };

    static WaypointGraph buildGraph() {
        WaypointGraph g = new WaypointGraph(waypoints);
        g.addEdge(0,1); g.addEdge(1,2); g.addEdge(2,3);   // top horizontal
        g.addEdge(1,4); g.addEdge(4,7); g.addEdge(7,10); g.addEdge(10,14); // center vertical
        g.addEdge(0,6); g.addEdge(6,9); g.addEdge(9,19);  // left vertical
        g.addEdge(2,5); g.addEdge(5,8); g.addEdge(8,11); g.addEdge(11,12); g.addEdge(12,20); // right vertical
        g.addEdge(4,5); g.addEdge(7,8);                   // mess horizontals
        g.addEdge(3,13); g.addEdge(11,13);                 // K-block
        g.addEdge(9,10); g.addEdge(10,12);                 // A/B horizontal
        g.addEdge(14,15); g.addEdge(14,16);                // P/N area
        g.addEdge(15,17); g.addEdge(16,18);
        g.addEdge(19,20);                                  // gate horizontal
        return g;
    }

    // ── Buildings ─────────────────────────────────────────────
    static final Building[] buildings = {
        new Building("Gate 1",      400,690, 60, 10, "Main entrance and exit gate. Open 24 hours."),
        new Building("Gate 2",      260,685, 15, 15, "Secondary pedestrian gate."),
        new Building("Gate 3",      420, 10, 35, 10, "North gate for service vehicles."),
        new Building("Parking Lot", 450,618,126, 58, "Parking for faculty and students. Open 6 AM–10 PM."),
        new Building("A Block",  295,330,130,50, "Academic block for CSE, ECE, and EEE."),
        new Building("B Block",  445,330,126,50, "Lecture halls for Science and Management."),
        new Building("N Block",  495,469, 83,147,"New academic block with smart classrooms."),
        new Building("P Block",  295,465, 83,150,"PG labs, research rooms, faculty offices."),
        new Building("N1 Block", 541,479, 34, 34,"Faculty cabins and meeting room."),
        new Building("N2 Block", 541,573, 34, 34,"Faculty extension, departmental offices."),
        new Building("M Block",  445,408,125, 60,"Computer labs, project rooms, server room."),
        new Building("H Block",  520, 25, 50, 35,"Admin: Registrar, exams, student services."),
        // Boys Hostel C-blocks
        new Building("C1",  399,255,22,35,"Boys hostel C1. Click floors to explore.",boysFloors),
        new Building("C1",  365,290,56,18,"Boys hostel C1 corridor.",boysFloors),
        new Building("C2",  297,290,68,18,"Boys hostel C2. Common room on ground floor.",boysFloors),
        new Building("C3",  343,236,22,54,"Boys hostel C3. 4-floor tower.",boysFloors),
        new Building("C4",  297,236,46,18,"Boys hostel C4.",boysFloors),
        new Building("C5",  297,200,23,36,"Boys hostel C5. Laundry on ground floor.",boysFloors),
        new Building("C6",  297,182,122,18,"Boys hostel C6. Long corridor.",boysFloors),
        new Building("C7",  297,145,23,37,"Boys hostel C7. Senior students.",boysFloors),
        new Building("C8",  297,127,45,18,"Boys hostel C8. Air-conditioned.",boysFloors),
        new Building("C9",  343, 95,23,50,"Boys hostel C9. Rooftop study area.",boysFloors),
        new Building("C10", 300, 77,66,18,"Boys hostel C10. First-year students.",boysFloors),
        new Building("C11", 366, 77,50,18,"Boys hostel C11.",boysFloors),
        new Building("C11", 398, 77,20,45,"Boys hostel C11 tower.",boysFloors),
        new Building("C12", 300, 25,120,20,"Boys hostel C12. Largest block, 120 students.",boysFloors),
        // Girls Hostel D-blocks
        new Building("D1",  447,255,22,35,"Girls hostel D1. 24-hour CCTV.",girlsFloors),
        new Building("D1",  447,290,54,17,"Girls hostel D1 corridor.",girlsFloors),
        new Building("D2",  500,290,67,17,"Girls hostel D2. Reading room attached.",girlsFloors),
        new Building("D3",  500,253,20,36,"Girls hostel D3. 4-floor with lifts.",girlsFloors),
        new Building("D4",  500,236,45,17,"Girls hostel D4. Overlooks central lawn.",girlsFloors),
        new Building("D5",  545,182,22,71,"Girls hostel D5. Postgraduate women.",girlsFloors),
        new Building("D6",  447,182,97,18,"Girls hostel D6. Covered walkway.",girlsFloors),
        // Sports
        new Building("K Block",          598, 30,100,65,"Sports complex: gym, table tennis, indoor games."),
        new Building("Padel Tennis",     350, 58, 71,18,"Padel tennis. Book via sports office."),
        new Building("Pickleball 1",     453, 63, 63,23,"Pickleball court #1. Open 6 AM–8 PM."),
        new Building("Pickleball 2",     700,130, 26,43,"Pickleball court #2. Tournaments."),
        new Building("Pickleball 3",     700,208, 26,23,"Pickleball court #3. Beginners."),
        new Building("Lawn Tennis 1",    700, 45, 26,38,"Hard court, floodlit."),
        new Building("Lawn Tennis 2",    700, 85, 26,43,"University team practice."),
        new Building("Volleyball Court", 453, 27, 40,35,"Outdoor court with coaching."),
        new Building("Basketball Court", 598,105,100,20,"Full-size court with seating."),
        new Building("Football Ground",  598,128,100,132,"FIFA turf. University home ground."),
        new Building("Cricket Ground",   250,549, 14,20,"Practice nets. Full turf pitch."),
        new Building("German Hanger",    410,487, 50,120,"Hanger for indoor sports and events."),
        // Mess & Hangout
        new Building("Mess (Goble)",     450, 98, 85,77,"Main mess. Breakfast, lunch, dinner. Cap 300."),
        new Building("Snapeats",         510,390, 20,10,"Snack outlet. Sandwiches and shakes."),
        new Building("Hotspot",          399,243, 22,10,"Hangout with Wi-Fi, beverages, snacks."),
        new Building("Southern Stories", 447,243, 22,10,"South Indian cuisine. Lunch and dinner."),
    };

    // ── Classroom buildings for navigation dropdown ────────────
    static Building[] classrooms;

    // ── Assign each building its nearest waypoint ──────────────
    static void assignWaypoints() {
        Map<String, Integer> wpMap = new HashMap<>();
        wpMap.put("A Block",10); wpMap.put("B Block",10); wpMap.put("N Block",16);
        wpMap.put("P Block",15); wpMap.put("M Block",10); wpMap.put("H Block",2);
        wpMap.put("N1 Block",16); wpMap.put("N2 Block",18);
        
        // Gates and Parking
        wpMap.put("Gate 1",19); wpMap.put("Gate 2",19); wpMap.put("Gate 3",1); wpMap.put("Parking Lot",18);
        
        // Sports
        wpMap.put("K Block",3); wpMap.put("Padel Tennis", 4); wpMap.put("Pickleball 1", 5);
        wpMap.put("Pickleball 2", 13); wpMap.put("Pickleball 3", 13);
        wpMap.put("Lawn Tennis 1", 3); wpMap.put("Lawn Tennis 2", 3);
        wpMap.put("Volleyball Court", 5); wpMap.put("Basketball Court", 5);
        wpMap.put("Football Ground", 8); wpMap.put("Cricket Ground", 9);
        wpMap.put("German Hanger", 14);
        
        // Mess & Hangout
        wpMap.put("Mess (Goble)", 4); wpMap.put("Snapeats", 16);
        wpMap.put("Hotspot", 7); wpMap.put("Southern Stories", 7);

        for (Building b : buildings) {
            if (wpMap.containsKey(b.name)) {
                b.waypointId = wpMap.get(b.name);
            } else {
                // Find dynamically closest waypoint for any building without a hardcoded one (like Hostels)
                int closestId = -1;
                double minDist = Double.MAX_VALUE;
                for (Waypoint wp : waypoints) {
                    double dist = Math.hypot(b.centreX() - wp.x, b.centreY() - wp.y);
                    if (dist < minDist) {
                        minDist = dist;
                        closestId = wp.id;
                    }
                }
                b.waypointId = closestId;
            }
        }
    }

    static MapPanel mapPanel;
    static JLabel   statusLabel;
    static JTextArea infoArea;

    public static void main(String[] args) {
        assignWaypoints();

        // Classroom list for nav overlay
        List<Building> cls = new ArrayList<>();
        Set<String> clsNames = new HashSet<>(Arrays.asList(
            "A Block","B Block","N Block","P Block","M Block","H Block","N1 Block","N2 Block"));
        for (Building b : buildings) if (clsNames.contains(b.name) && !cls.contains(b)) cls.add(b);
        classrooms = cls.toArray(new Building[0]);

        WaypointGraph graph = buildGraph();

        JFrame window = new JFrame("Campus Navigation System");
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.setSize(1100, 800);
        window.setLayout(new BorderLayout());
        window.getRootPane().setBorder(BorderFactory.createEmptyBorder());
        // Dark content pane
        ((JPanel)window.getContentPane()).setBackground(new Color(10, 15, 35));

        statusLabel = new JLabel("  🗺  Click a building on the map — Hostels show floor/room drill-down  |  Use right panel for A→B routing");
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        statusLabel.setOpaque(true);
        statusLabel.setBackground(new Color(18, 28, 65));
        statusLabel.setForeground(new Color(160, 210, 255));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0,0,1,0,new Color(60,100,200)),
            BorderFactory.createEmptyBorder(8,12,8,12)));
        window.add(statusLabel, BorderLayout.NORTH);

        mapPanel = new MapPanel(buildings, paths, graph, classrooms);
        mapPanel.setPreferredSize(new Dimension(500, 700));
        window.add(mapPanel, BorderLayout.CENTER);

        // Right panel: building buttons + route info
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBackground(new Color(12, 18, 42));
        rightPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0,1,0,0,new Color(40,70,160)),
            BorderFactory.createEmptyBorder(6,6,6,6)));

        JPanel buttonPanel = new JPanel(new GridLayout(buildings.length+1, 1, 3, 3));
        buttonPanel.setBackground(new Color(12, 18, 42));
        javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(60,100,200),1,true), "  Select Building  ");
        tb.setTitleColor(new Color(120,170,255));
        tb.setTitleFont(new Font("SansSerif", Font.BOLD, 12));
        buttonPanel.setBorder(tb);

        JButton resetBtn = new JButton("⟳  Reset Map");
        resetBtn.setBackground(new Color(160, 30, 50));
        resetBtn.setForeground(Color.WHITE);
        resetBtn.setFocusPainted(false);
        resetBtn.setBorderPainted(false);
        resetBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        resetBtn.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        resetBtn.addActionListener(e -> {
            mapPanel.start = null; mapPanel.end = null;
            mapPanel.clickedBuilding = null; mapPanel.selectedFloor = null;
            mapPanel.selectedRoom = null; mapPanel.navPath = null; mapPanel.navSource = null; mapPanel.navTarget = null;
            mapPanel.routePath = null;
            mapPanel.dismissTimer.stop();
            statusLabel.setText("  🗺  Cleared. Click a building to begin.");
            infoArea.setText("Select two buildings to find a route.");
            mapPanel.repaint();
        });
        buttonPanel.add(resetBtn);

        Color[] btnBg = {
            new Color(20,50,120), new Color(50,25,100), new Color(15,70,45),
            new Color(100,50,15), new Color(18,28,65)
        };
        int bIdx = 0;
        for (Building b : buildings) {
            JButton btn = new JButton(b.name);
            String nm = b.name;
            Color bg;
            if (b.isHostel()) bg = new Color(50,25,100);
            else if (nm.contains("Block") && !nm.equals("K Block")) bg = new Color(20,50,120);
            else if (nm.contains("Gate")||nm.contains("Parking")) bg = new Color(80,60,15);
            else if (nm.contains("Tennis")||nm.contains("Pickleball")||nm.equals("K Block")
                    ||nm.contains("Volleyball")||nm.contains("Basketball")
                    ||nm.contains("Football")||nm.contains("Cricket")||nm.contains("Hanger")||nm.contains("Padel"))
                bg = new Color(15,65,40);
            else if (nm.contains("Mess")||nm.contains("Snapeats")||nm.contains("Hotspot")||nm.contains("Stories"))
                bg = new Color(90,40,10);
            else bg = new Color(18,28,65);
            btn.setBackground(bg);
            btn.setForeground(new Color(200,220,255));
            btn.setFocusPainted(false);
            btn.setBorderPainted(false);
            btn.setFont(new Font("SansSerif", Font.PLAIN, 11));
            btn.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            btn.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    btn.setBackground(btn.getBackground().brighter().brighter());
                }
                public void mouseExited(java.awt.event.MouseEvent e) { btn.setBackground(bg); }
            });
            btn.addActionListener(e -> {
                mapPanel.clickedBuilding = b; mapPanel.selectedFloor = null;
                mapPanel.selectedRoom = null; mapPanel.navPath = null; mapPanel.showClassList = false;
                mapPanel.resetDismissTimer(); mapPanel.repaint();
                buildingSelected(b);
            });
            buttonPanel.add(btn);
        }

        infoArea = new JTextArea(6, 20);
        infoArea.setEditable(false);
        infoArea.setFont(new Font("Courier New", Font.PLAIN, 11));
        infoArea.setLineWrap(true); infoArea.setWrapStyleWord(true);
        infoArea.setText("Select two buildings to find a route.");
        infoArea.setBackground(new Color(10,16,38)); infoArea.setForeground(new Color(160,210,255));
        infoArea.setCaretColor(new Color(100,160,255));
        javax.swing.border.TitledBorder ib = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(60,100,200),1,true), "  Route Info  ");
        ib.setTitleColor(new Color(120,170,255));
        ib.setTitleFont(new Font("SansSerif", Font.BOLD, 11));
        infoArea.setBorder(ib);

        JScrollPane btnScroll = new JScrollPane(buttonPanel);
        btnScroll.setBackground(new Color(12,18,42));
        btnScroll.getViewport().setBackground(new Color(12,18,42));
        btnScroll.setBorder(BorderFactory.createEmptyBorder());

        JScrollPane infoScroll = new JScrollPane(infoArea);
        infoScroll.setBackground(new Color(10,16,38));
        infoScroll.getViewport().setBackground(new Color(10,16,38));
        infoScroll.setBorder(BorderFactory.createEmptyBorder());

        rightPanel.add(btnScroll,  BorderLayout.CENTER);
        rightPanel.add(infoScroll, BorderLayout.SOUTH);
        window.add(rightPanel, BorderLayout.EAST);

        JLabel bottomBar = new JLabel(
            "  💡  Click map building → info card (auto-hides 4s)  |  Hostels: Floor → Room → Navigate  |  Right panel: A → B routing");
        bottomBar.setFont(new Font("SansSerif", Font.ITALIC, 11));
        bottomBar.setOpaque(true);
        bottomBar.setBackground(new Color(14, 22, 55));
        bottomBar.setForeground(new Color(100, 140, 220));
        bottomBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1,0,0,0,new Color(40,70,160)),
            BorderFactory.createEmptyBorder(6,12,6,12)));
        window.add(bottomBar, BorderLayout.SOUTH);

        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    static void buildingSelected(Building clicked) {
        if (mapPanel.start == null) {
            mapPanel.start = clicked;
            mapPanel.routePath = null;
            statusLabel.setText("  START: " + clicked.name + "  |  Click another to set END.");
            infoArea.setText("START: " + clicked + "\n\nClick another building to set END.");
        } else if (mapPanel.end == null && clicked != mapPanel.start) {
            mapPanel.end = clicked;
            
            // Calculate waypoint route
            int wpStart = mapPanel.start.waypointId;
            int wpEnd = mapPanel.end.waypointId;
            WaypointGraph graph = mapPanel.graph;
            mapPanel.routePath = (wpStart < 0 || wpEnd < 0) ? new int[0] : graph.findPath(wpStart, wpEnd);
            
            // Calculate distance along the route
            double totalMeters = 0;
            if (mapPanel.routePath != null && mapPanel.routePath.length > 0) {
                // Building -> First Waypoint
                Waypoint firstWP = graph.points[mapPanel.routePath[0]];
                totalMeters += Math.hypot(mapPanel.start.centreX() - firstWP.x, mapPanel.start.centreY() - firstWP.y);
                
                // Waypoint to Waypoint
                for (int i = 0; i < mapPanel.routePath.length - 1; i++) {
                    Waypoint w1 = graph.points[mapPanel.routePath[i]];
                    Waypoint w2 = graph.points[mapPanel.routePath[i+1]];
                    totalMeters += Math.hypot(w1.x - w2.x, w1.y - w2.y);
                }
                
                // Last Waypoint -> Building
                Waypoint lastWP = graph.points[mapPanel.routePath[mapPanel.routePath.length - 1]];
                totalMeters += Math.hypot(mapPanel.end.centreX() - lastWP.x, mapPanel.end.centreY() - lastWP.y);
            } else {
                // Fallback to straight line if no path
                totalMeters = mapPanel.start.distanceTo(clicked);
            }
            
            // Convert pixels to approximate meters (assuming 1 pixel = ~2 meters)
            totalMeters *= 2.0;
            
            statusLabel.setText("  Route: " + mapPanel.start.name + " → " + clicked.name +
                    "  |  ~" + String.format("%.0f", totalMeters) + " m");
            infoArea.setText("=== ROUTE ===\nFROM: " + mapPanel.start +
                    "\nTO:   " + clicked +
                    "\n\nDist: ~" + String.format("%.0f", totalMeters) + " metres via roads\n\nClick Reset to clear.");
        } else {
            mapPanel.start = clicked; mapPanel.end = null;
            mapPanel.routePath = null;
            statusLabel.setText("  New START: " + clicked.name + "  |  Click another to set END.");
            infoArea.setText("START: " + clicked + "\n\nClick another building to set END.");
        }
        mapPanel.repaint();
    }

    // ── Road paths ─────────────────────────────────────────────
    static CampusPath[] paths = {
        new CampusPath(432, 20,  10,450,false),
        new CampusPath(278,322, 305,  8,false),
        new CampusPath(260, 18, 480,  8,false),
        new CampusPath(260, 18,  10,665,false),
        new CampusPath(580, 20,   8,670,false),
        new CampusPath(253,312,  27, 27,true),
        new CampusPath(260,682, 320,  8,false),
        new CampusPath(378,470, 118,  8,false),
        new CampusPath(378,470,   8,145,false),
        new CampusPath(488,470,   8,145,false),
        new CampusPath(732, 26,   8,270,false),
        new CampusPath(588,288, 146,  8,false),
        new CampusPath(286, 26,   8,270,false),
        new CampusPath(440, 94, 146,  8,false),
        new CampusPath(440,175, 146,  8,false),
        new CampusPath(270,177,  20,  8,false),
        new CampusPath(270,288,  20,  8,false),
    };
}
