package com.queueless;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@SpringBootApplication
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class QueueLessApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueueLessApplication.class, args);
    }

    private final JdbcTemplate db;

    public QueueLessApplication(JdbcTemplate db) {
        this.db = db;
    }

    // "Today" in Indian time, used for daily queue numbers and daily report
    private static final String TODAY =
            "(joined_at AT TIME ZONE 'Asia/Kolkata')::date = (NOW() AT TIME ZONE 'Asia/Kolkata')::date";

    // ---------- helpers ----------

    private int count(String sql, Object... args) {
        Integer n = db.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int counters() {
        return Math.max(1, count("SELECT COUNT(*) FROM service_counter"));
    }

    // Average service time in minutes, from the last 50 completed services (default 5)
    private double avgMinutes() {
        Double s = db.queryForObject(
                "SELECT COALESCE(AVG(service_duration),0) FROM " +
                "(SELECT service_duration FROM queue_history ORDER BY history_id DESC LIMIT 50) t",
                Double.class);
        if (s == null || s == 0) return 5.0;
        return Math.max(0.1, Math.round(s / 6.0) / 10.0);
    }

    // Waiting time = (people ahead + 1) x average service time / number of counters
    private int estimate(int ahead, double avg, int counters) {
        return (int) Math.ceil((ahead + 1) * avg / counters);
    }

    private ResponseEntity<?> err(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private ResponseEntity<?> ok() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Map<String, Object> statusOf(int number) {
        List<Map<String, Object>> r = db.queryForList(
                "SELECT c.customer_id, c.customer_name, c.queue_number, q.status, sc.counter_name " +
                "FROM customer c JOIN queue q ON q.customer_id = c.customer_id " +
                "LEFT JOIN service_counter sc ON sc.counter_id = c.counter_id " +
                "WHERE c.queue_number = ? AND " + TODAY + " ORDER BY c.customer_id DESC LIMIT 1", number);
        if (r.isEmpty()) return null;
        Map<String, Object> m = r.get(0);
        if ("WAITING".equals(m.get("status"))) {
            int ahead = count("SELECT COUNT(*) FROM queue WHERE status='WAITING' AND customer_id < ?",
                    m.get("customer_id"));
            m.put("position", ahead + 1);
            m.put("estimatedWait", estimate(ahead, avgMinutes(), counters()));
        }
        return m;
    }

    // ---------- general ----------

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    // ---------- customers ----------

    @PostMapping("/customers")
    public ResponseEntity<?> join(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty()) return err("Customer name is required");
        String phone = body.getOrDefault("phone", "").trim();

        int number = count("SELECT COALESCE(MAX(queue_number),0)+1 FROM customer WHERE " + TODAY);
        int ahead = count("SELECT COUNT(*) FROM queue WHERE status='WAITING'");
        int wait = estimate(ahead, avgMinutes(), counters());

        Integer id = db.queryForObject(
                "INSERT INTO customer (customer_name, phone, queue_number) VALUES (?,?,?) RETURNING customer_id",
                Integer.class, name, phone, number);
        db.update("INSERT INTO queue (customer_id, status, estimated_wait) VALUES (?, 'WAITING', ?)", id, wait);

        return ResponseEntity.ok(statusOf(number));
    }

    @PostMapping("/customers/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable int id) {
        db.update("UPDATE queue SET status='CANCELLED' WHERE customer_id=? AND status='WAITING'", id);
        return ok();
    }

    @GetMapping("/status/{number}")
    public ResponseEntity<?> status(@PathVariable int number) {
        Map<String, Object> m = statusOf(number);
        if (m == null) return err("Queue number not found for today");
        return ResponseEntity.ok(m);
    }

    // ---------- live queue ----------

    @GetMapping("/queue")
    public Map<String, Object> queue() {
        double avg = avgMinutes();
        int c = counters();
        List<Map<String, Object>> waiting = db.queryForList(
                "SELECT c.customer_id, c.customer_name, c.queue_number, " +
                "to_char(c.joined_at AT TIME ZONE 'Asia/Kolkata','HH12:MI AM') AS joined " +
                "FROM queue q JOIN customer c ON c.customer_id = q.customer_id " +
                "WHERE q.status='WAITING' ORDER BY q.queue_id");
        int i = 0;
        for (Map<String, Object> w : waiting) {
            w.put("position", i + 1);
            w.put("estimatedWait", estimate(i, avg, c));
            i++;
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("waiting", waiting);
        res.put("avgServiceMinutes", avg);
        return res;
    }

    // ---------- counters ----------

    @GetMapping("/counters")
    public List<Map<String, Object>> listCounters() {
        return db.queryForList(
                "SELECT sc.counter_id, sc.counter_name, sc.status, " +
                "s.customer_name AS serving_name, s.queue_number AS serving_number " +
                "FROM service_counter sc LEFT JOIN (" +
                "  SELECT c.customer_name, c.queue_number, c.counter_id FROM customer c " +
                "  JOIN queue q ON q.customer_id = c.customer_id WHERE q.status='SERVING'" +
                ") s ON s.counter_id = sc.counter_id ORDER BY sc.counter_id");
    }

    @PostMapping("/counters")
    public ResponseEntity<?> addCounter(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty()) return err("Counter name is required");
        db.update("INSERT INTO service_counter (counter_name) VALUES (?)", name);
        return ok();
    }

    @DeleteMapping("/counters/{id}")
    public ResponseEntity<?> deleteCounter(@PathVariable int id) {
        if (count("SELECT COUNT(*) FROM service_counter WHERE counter_id=? AND status='BUSY'", id) > 0)
            return err("Counter is busy. Complete the service first.");
        db.update("DELETE FROM service_counter WHERE counter_id=?", id);
        return ok();
    }

    // Call the next waiting customer to this counter
    @PostMapping("/counters/{id}/serve")
    public ResponseEntity<?> serve(@PathVariable int id) {
        List<Map<String, Object>> c = db.queryForList("SELECT status FROM service_counter WHERE counter_id=?", id);
        if (c.isEmpty()) return err("Counter not found");
        if (!"FREE".equals(c.get(0).get("status"))) return err("Counter is busy");

        List<Map<String, Object>> next = db.queryForList(
                "SELECT queue_id, customer_id FROM queue WHERE status='WAITING' ORDER BY queue_id LIMIT 1");
        if (next.isEmpty()) return err("No customers are waiting");

        db.update("UPDATE queue SET status='SERVING', served_at=NOW() WHERE queue_id=?", next.get(0).get("queue_id"));
        db.update("UPDATE customer SET counter_id=? WHERE customer_id=?", id, next.get(0).get("customer_id"));
        db.update("UPDATE service_counter SET status='BUSY' WHERE counter_id=?", id);
        return ok();
    }

    // Finish the current service at this counter and save it to history
    @PostMapping("/counters/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable int id) {
        List<Map<String, Object>> s = db.queryForList(
                "SELECT q.queue_id, q.customer_id, EXTRACT(EPOCH FROM (NOW() - q.served_at))::int AS secs " +
                "FROM queue q JOIN customer c ON c.customer_id = q.customer_id " +
                "WHERE q.status='SERVING' AND c.counter_id=? LIMIT 1", id);
        if (s.isEmpty()) return err("No customer is being served at this counter");

        int secs = Math.max(1, ((Number) s.get(0).get("secs")).intValue());
        db.update("UPDATE queue SET status='DONE' WHERE queue_id=?", s.get(0).get("queue_id"));
        db.update("INSERT INTO queue_history (customer_id, service_duration) VALUES (?, ?)",
                s.get(0).get("customer_id"), secs);
        db.update("UPDATE service_counter SET status='FREE' WHERE counter_id=?", id);
        return ok();
    }

    // ---------- daily report ----------

    @GetMapping("/report")
    public Map<String, Object> report() {
        Double avg = db.queryForObject(
                "SELECT COALESCE(AVG(h.service_duration),0) FROM queue_history h " +
                "JOIN customer c ON c.customer_id = h.customer_id WHERE " + TODAY, Double.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalCustomers", count("SELECT COUNT(*) FROM customer WHERE " + TODAY));
        m.put("served", count("SELECT COUNT(*) FROM queue q JOIN customer c ON c.customer_id = q.customer_id " +
                "WHERE q.status='DONE' AND " + TODAY));
        m.put("waiting", count("SELECT COUNT(*) FROM queue WHERE status='WAITING'"));
        m.put("serving", count("SELECT COUNT(*) FROM queue WHERE status='SERVING'"));
        m.put("avgServiceMinutes", Math.round(avg / 6.0) / 10.0);
        return m;
    }
}
