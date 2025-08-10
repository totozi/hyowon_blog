package com.blog.hyowon.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Notion Exporter (페이지 트리 → 여러 HTML 파일)
 * - 루트: index.html
 * - 하위 페이지: <slug>-<id8>.html 로 생성
 * - child_database도 rows를 페이지로 간주해서 각각 파일 생성
 */
public class NotionExporter {

    // 리다이렉트 허용 + UA 세팅용
    private final java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    // 같은 실행 중 중복 다운로드 방지 (옵션)
    private final java.util.Set<String> downloadedKeys = new java.util.concurrent.ConcurrentSkipListSet<>();

    private final java.util.Set<String> touchedImages = new java.util.concurrent.ConcurrentSkipListSet<>();

    private static final String NOTION_VERSION = "2022-06-28";

    private final WebClient http;
    private final ObjectMapper om = new ObjectMapper();

    private Path outputDir; // ▼ 추가

    public NotionExporter(String token) {
        this.http = WebClient.builder()
                .baseUrl("https://api.notion.com/v1")
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Notion-Version", NOTION_VERSION)
                .build();
    }

    // public void fileDown(String[] args) throws Exception {
    // String token = notionConfig.getApiKey(); // secret_xxx (integration 토큰)
    // String pageId = notionConfig.getPageId(); // 루트 페이지 ID
    // if (token == null || pageId == null) {
    // System.err.println("환경변수 NOTION_TOKEN / NOTION_PAGE_ID 세팅해라.");
    // System.exit(1);
    // }
    // Path outDir = Paths.get("src/main/resources/static/notion_export");
    // new NotionExporter(token).exportSite(pageId, outDir);

    // // 로그 찍기
    // long htmlCount = Files.walk(outDir)
    // .filter(p -> p.toString().endsWith(".html"))
    // .count();
    // long imgCount = Files.walk(outDir.resolve("assets/images"))
    // .filter(Files::isRegularFile)
    // .count();

    // System.out.printf("[%s] Notion Export 완료 - HTML: %d개, 이미지: %d개%n",
    // java.time.LocalDateTime.now(), htmlCount, imgCount);

    // }
    /* ===================== Public API ===================== */

    /** 루트 페이지부터 시작해 사이트 형태로 폴더에 떨어뜨림 */
    public void exportSite(String rootPageId, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        this.outputDir = outDir;

        // ▼ 이번 실행 기록 초기화
        touchedImages.clear();

        writePageFile(hyphenize(rootPageId), outDir, "index.html", 1);

        // ▼ 사용되지 않은(고아) 이미지 삭제
        cleanupOrphanImages(); // <-- 추가

        System.out.println("DONE → " + outDir.toAbsolutePath());
    }

    /* ===================== Core ===================== */

    /** 단일 페이지를 파일로 생성하고, child_page / child_db는 재귀로 생성 */
    private void writePageFile(String pageId, Path dir, String fileName, int depth) throws Exception {
        String title = getPageTitle(pageId);
        List<JsonNode> blocks = fetchAllChildren(pageId);

        // 자식 페이지/DB 수집
        List<JsonNode> childPages = new ArrayList<>();
        List<JsonNode> childDBs = new ArrayList<>();
        for (JsonNode b : blocks) {
            String type = b.path("type").asText();
            if ("child_page".equals(type))
                childPages.add(b);
            else if ("child_database".equals(type))
                childDBs.add(b);
        }

        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html><html><head><meta charset='UTF-8'>
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>""").append(esc(title))
                .append("""
                        </title>
                        <style>
                          body{font-family:system-ui,-apple-system,Segoe UI,Roboto,Arial,sans-serif;line-height:1.6;padding:24px;max-width:900px;margin:auto}
                          h1,h2,h3,h4{margin-top:1.6em}
                          pre,code{background:#f4f5f7;border-radius:6px}
                          pre{padding:12px;overflow:auto}
                          blockquote{border-left:4px solid #ddd;margin:1em 0;padding:.5em 1em;color:#555}
                          hr{border:0;border-top:1px solid #eee;margin:2em 0}
                          .callout{border:1px solid #eee;border-left:4px solid #999;padding:12px;border-radius:8px;background:#fafafa}
                          .toc ul{margin:0 0 1rem 1.25rem}
                          .topnav{font-size:.9rem;margin-bottom:1rem}
                          .topnav a{opacity:.8;text-decoration:none}
                          .notion-table{border-collapse:collapse;width:100%;margin:1em 0}
                          .notion-table th,.notion-table td{border:1px solid #ddd;padding:8px;vertical-align:top}
                          .notion-table thead th{background:#f7f7f7}

                          /* Notion-like table */
                            .notion-table-wrap{overflow-x:auto;margin:8px 0}
                            .notion-table{
                            border-collapse:collapse;
                            /* 핵심: 컨테이너 꽉 채우지 말고 내용 폭 기준으로 */
                            width:auto; max-width:100%;
                            table-layout:auto;
                            }
                            .notion-table th,.notion-table td{
                            border:1px solid rgba(55,53,47,.16);
                            padding:8px 12px;
                            vertical-align:top;
                            white-space:pre-wrap; word-break:break-word;
                            }
                            .notion-table thead th{
                            background:rgba(55,53,47,.06);
                            font-weight:600;
                            }
                            .notion-table th[scope="row"]{
                            background:rgba(55,53,47,.03);
                            font-weight:600;
                            }
                            .notion-table tbody tr:hover td{
                            background:rgba(55,53,47,.04);
                            }
                        </style></head><body>
                        """);

        // 상단 네비(루트 아니면 index로 가는 링크)
        if (depth > 1)
            html.append("<div class='topnav'><a href='index.html'>&larr; Back to Index</a></div>");

        html.append("<h1>").append(esc(title)).append("</h1>");

        // 본문 블록 렌더(자식 페이지 내용은 여기서 렌더하지 않음)
        renderBlocks(blocks, html);

        // 자식 페이지 링크 섹션
        // 자식 페이지 링크 섹션
        if (!childPages.isEmpty() || !childDBs.isEmpty()) {
            html.append("<section class='toc'><h2>Subpages</h2><ul>"); // ★ 열기 추가
            for (JsonNode c : childPages) {
                String cid = c.path("id").asText();
                String ct = c.path("child_page").path("title").asText("(Untitled)");
                String childFile = slug(ct, cid) + ".html";
                html.append("<li><a href='").append(childFile).append("'>").append(esc(ct)).append("</a></li>");
            }
            for (JsonNode db : childDBs) {
                String dbId = db.path("id").asText();
                String dbTitle = "(Database)";
                List<String> rowIds = queryDatabasePages(dbId);
                if (!rowIds.isEmpty()) {
                    html.append("<li>").append(esc(dbTitle)).append("<ul>");
                    for (String rowPid : rowIds) {
                        String rt = getPageTitle(rowPid);
                        String rf = slug(rt, rowPid) + ".html";
                        html.append("<li><a href='").append(rf).append("'>").append(esc(rt)).append("</a></li>");
                    }
                    html.append("</ul></li>");
                }
            }
            html.append("</ul></section>"); // 닫기
        }

        html.append("</body></html>");
        Files.writeString(dir.resolve(fileName), html.toString());

        // 자식 페이지 파일 생성 (재귀)
        for (JsonNode c : childPages) {
            String cid = c.path("id").asText();
            String ct = c.path("child_page").path("title").asText("(Untitled)");
            writePageFile(cid, dir, slug(ct, cid) + ".html", depth + 1);
        }
        // DB의 row들도 각각 파일로 생성
        for (JsonNode db : childDBs) {
            String dbId = db.path("id").asText();
            for (String rowPid : queryDatabasePages(dbId)) {
                String rt = getPageTitle(rowPid);
                writePageFile(rowPid, dir, slug(rt, rowPid) + ".html", depth + 1);
            }
        }
    }

    /* ===================== Render ===================== */

    private void renderBlocks(List<JsonNode> blocks, StringBuilder out) {
        boolean inUL = false, inOL = false, inTODO = false;
        for (JsonNode b : blocks) {
            String t = b.path("type").asText();

            // 리스트 경계 처리
            if (!"bulleted_list_item".equals(t) && inUL) {
                out.append("</ul>");
                inUL = false;
            }
            if (!"numbered_list_item".equals(t) && inOL) {
                out.append("</ol>");
                inOL = false;
            }
            if (!"to_do".equals(t) && inTODO) {
                out.append("</ul>");
                inTODO = false;
            }

            switch (t) {
                case "paragraph" -> p(out, renderText(b.path("paragraph").path("rich_text")));
                case "heading_1" -> h(out, 2, renderText(b.path("heading_1").path("rich_text")));
                case "heading_2" -> h(out, 3, renderText(b.path("heading_2").path("rich_text")));
                case "heading_3" -> h(out, 4, renderText(b.path("heading_3").path("rich_text")));
                case "quote" -> out.append("<blockquote>").append(renderText(b.path("quote").path("rich_text")))
                        .append("</blockquote>");
                case "divider" -> out.append("<hr/>");
                case "callout" -> out.append("<div class='callout'>")
                        .append(renderText(b.path("callout").path("rich_text"))).append("</div>");
                case "code" -> {
                    String lang = b.path("code").path("language").asText("");
                    String code = concatPlain(b.path("code").path("rich_text"));
                    out.append("<pre><code class='language-").append(esc(lang)).append("'>")
                            .append(esc(code)).append("</code></pre>");
                }
                case "bulleted_list_item" -> {
                    if (!inUL) {
                        out.append("<ul>");
                        inUL = true;
                    }
                    out.append("<li>").append(renderText(b.path("bulleted_list_item").path("rich_text")))
                            .append("</li>");
                }
                case "numbered_list_item" -> {
                    if (!inOL) {
                        out.append("<ol>");
                        inOL = true;
                    }
                    out.append("<li>").append(renderText(b.path("numbered_list_item").path("rich_text")))
                            .append("</li>");
                }
                case "to_do" -> {
                    if (!inTODO) {
                        out.append("<ul>");
                        inTODO = true;
                    }
                    boolean checked = b.path("to_do").path("checked").asBoolean(false);
                    out.append("<li><input type='checkbox' disabled ")
                            .append(checked ? "checked" : "")
                            .append("/> ")
                            .append(renderText(b.path("to_do").path("rich_text")))
                            .append("</li>");
                }
                case "table" -> {
                    try {
                        renderTable(b, out);
                    } catch (Exception e) {
                        System.out.println("[TABLE] error: " + e.getMessage());
                    }
                }
                case "image" -> {
                    JsonNode img = b.path("image");
                    String url = "external".equals(img.path("type").asText())
                            ? img.path("external").path("url").asText("")
                            : img.path("file").path("url").asText("");

                    if (!url.isEmpty()) {
                        String local = downloadImage(url); // ▼ 추가
                        String src = (local != null) ? local : url;
                        out.append("<p><img src='").append(esc(src))
                                .append("' style='max-width:100%'/></p>");
                    }
                }
                // child_page / child_database 는 파일 생성 단계에서 처리(여기선 건너뜀)
                default -> {
                }
            }
        }
        if (inUL)
            out.append("</ul>");
        if (inOL)
            out.append("</ol>");
        if (inTODO)
            out.append("</ul>");
    }

    /* ===================== Notion REST ===================== */

    public String getPageTitle(String pageId) {
        pageId = hyphenize(pageId);
        String body = http.get()
                .uri("/pages/{id}", pageId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        try {
            JsonNode props = om.readTree(body).path("properties");
            if (props.isObject()) {
                Iterator<String> it = props.fieldNames();
                while (it.hasNext()) {
                    JsonNode p = props.get(it.next());
                    if ("title".equals(p.path("type").asText())) {
                        JsonNode arr = p.path("title");
                        if (arr.isArray() && arr.size() > 0)
                            return arr.get(0).path("plain_text").asText("(Untitled)");
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return "(Untitled)";
    }

    private List<JsonNode> fetchAllChildren(String blockOrPageId) throws Exception {
        blockOrPageId = hyphenize(blockOrPageId);
        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        do {
            String uri = "/blocks/" + blockOrPageId + "/children?page_size=100" +
                    (cursor != null ? "&start_cursor=" + cursor : "");
            String body = http.get().uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = om.readTree(body);
            if (root.has("results"))
                for (JsonNode n : root.get("results"))
                    all.add(n);
            cursor = root.path("has_more").asBoolean(false) ? root.path("next_cursor").asText(null) : null;
        } while (cursor != null);
        return all;
    }

    private List<String> queryDatabasePages(String dbId) throws Exception {
        dbId = hyphenize(dbId);
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            String uri = "/databases/{id}/query" + (cursor != null ? "?start_cursor=" + cursor : "");
            String body = http.post().uri(uri, dbId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = om.readTree(body);
            if (root.has("results"))
                for (JsonNode r : root.get("results")) {
                    String pid = r.path("id").asText();
                    if (!pid.isBlank())
                        ids.add(pid);
                }
            cursor = root.path("has_more").asBoolean(false) ? root.path("next_cursor").asText(null) : null;
        } while (cursor != null);
        return ids;
    }

    /* ===================== Helpers ===================== */

    private static String renderText(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.size() == 0)
            return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : arr) {
            String s = esc(t.path("plain_text").asText(""));
            String href = t.path("href").asText("");
            JsonNode an = t.path("annotations");
            boolean code = an.path("code").asBoolean(false);
            if (code)
                s = "<code>" + s + "</code>";
            if (an.path("bold").asBoolean(false))
                s = "<strong>" + s + "</strong>";
            if (an.path("italic").asBoolean(false))
                s = "<em>" + s + "</em>";
            if (an.path("underline").asBoolean(false))
                s = "<u>" + s + "</u>";
            if (an.path("strikethrough").asBoolean(false))
                s = "<s>" + s + "</s>";
            if (!href.isEmpty())
                s = "<a href='" + esc(href) + "'>" + s + "</a>";
            sb.append(s);
        }
        return sb.toString();
    }

    private static void p(StringBuilder out, String s) {
        if (!s.isBlank())
            out.append("<p>").append(s).append("</p>");
    }

    private static void h(StringBuilder out, int lv, String s) {
        if (!s.isBlank())
            out.append("<h").append(lv).append(">").append(s).append("</h").append(lv).append(">");
    }

    private static String concatPlain(JsonNode arr) {
        if (arr == null || !arr.isArray())
            return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : arr)
            sb.append(t.path("plain_text").asText(""));
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 주소에서 하이픈 없는 32자 들어오면 하이픈 삽입 */
    private static String hyphenize(String id) {
        String s = (id == null) ? "" : id.replaceAll("-", "");
        if (s.length() != 32)
            return id;
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20)
                + "-" + s.substring(20);
    }

    /** 파일명 슬러그: 제목 기반 + id 앞 8글자 */
    private static String slug(String title, String id) {
        String base = (title == null || title.isBlank()) ? "untitled"
                : title
                        .toLowerCase().replaceAll("[^a-z0-9\\-\\s]", "").trim().replaceAll("\\s+", "-");
        String shortId = id.replace("-", "");
        if (shortId.length() >= 8)
            shortId = shortId.substring(0, 8);
        return base + "-" + shortId;
    }

    private String downloadImage(String url) {
        try {
            Path imgDir = outputDir.resolve("assets/images");
            Files.createDirectories(imgDir);

            // 1) 쿼리 제거한 키
            String key = normalizedKey(url);

            // 2) 파일명 만들기 (원래 파일명 + 키 해시)
            String pure = key; // 이미 쿼리 제거됨
            String filename = pure.substring(pure.lastIndexOf('/') + 1);
            if (filename.isBlank())
                filename = "image";
            int dot = filename.lastIndexOf('.');
            String ext = (dot >= 0 && (filename.length() - dot) <= 5)
                    ? filename.substring(dot).toLowerCase()
                    : ".bin";
            String base = (dot >= 0 ? filename.substring(0, dot) : filename)
                    .toLowerCase().replaceAll("[^a-z0-9\\-]+", "-");
            String hash8 = java.util.UUID.nameUUIDFromBytes(pure.getBytes())
                    .toString().replace("-", "").substring(0, 8);
            String name = hash8 + "-" + base + ext;

            Path out = imgDir.resolve(name);

            touchedImages.add(name);

            // 3) 디스크 캐시: 이미 있으면 즉시 반환
            if (Files.exists(out)) {
                System.out.println("[IMG] cache(hit-disk) → " + out.getFileName());
                return "assets/images/" + name;
            }

            // 4) 런타임 캐시: 같은 실행 중 중복 스킵
            if (!downloadedKeys.add(key)) {
                System.out.println("[IMG] cache(hit-mem)  → " + out.getFileName());
                return "assets/images/" + name;
            }

            // 5) 다운로드 (UA + 리다이렉트)
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            var res = plainHttp.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            System.out.println("[IMG] status " + res.statusCode() + " len=" + res.body().length);

            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                Files.write(out, res.body());
                System.out.println("[IMG] downloaded      → " + out.getFileName());
                return "assets/images/" + name;
            } else {
                System.out.println("[IMG] fail " + res.statusCode() + " : " + url);
                return null; // 실패 시 외부 URL로 폴백
            }

        } catch (Exception e) {
            System.out.println("[IMG] error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
    }

    private static String normalizedKey(String url) {
        try {
            var u = java.net.URI.create(url);
            // scheme://host + path 만 사용 (쿼리/fragment 무시)
            return new java.net.URI(u.getScheme(), u.getHost(), u.getPath(), null).toString();
        } catch (Exception e) {
            int q = url.indexOf('?');
            return q > -1 ? url.substring(0, q) : url;
        }
    }

    private void cleanupOrphanImages() throws java.io.IOException {
        Path imgDir = outputDir.resolve("assets/images");
        if (!Files.isDirectory(imgDir))
            return;

        try (var stream = Files.list(imgDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String fname = p.getFileName().toString();
                if (!touchedImages.contains(fname)) {
                    try {
                        Files.delete(p);
                        System.out.println("[IMG] removed orphan → " + fname);
                    } catch (Exception e) {
                        System.out.println("[IMG] delete fail → " + fname + " : " + e.getMessage());
                    }
                }
            });
        }
    }

    /**
     * Notion table → HTML
     * <table>
     * 렌더링
     */
    private void renderTable(JsonNode tableBlock, StringBuilder out) throws Exception {
        JsonNode t = tableBlock.path("table");
        boolean hasColHeader = t.path("has_column_header").asBoolean(false);
        boolean hasRowHeader = t.path("has_row_header").asBoolean(false);

        // 자식에는 table_row 블록들이 들어있음
        String tableId = tableBlock.path("id").asText();
        List<JsonNode> rows = fetchAllChildren(tableId);

        // table_row만 추림
        List<JsonNode> rowBlocks = new ArrayList<>();
        for (JsonNode r : rows)
            if ("table_row".equals(r.path("type").asText()))
                rowBlocks.add(r);

        if (rowBlocks.isEmpty())
            return;

        out.append("<div class='notion-table-wrap'>");
        out.append("<table class='notion-table'>");

        int start = 0;
        // 컬럼 헤더 있으면 첫 행을 thead로
        if (hasColHeader) {
            JsonNode head = rowBlocks.get(0).path("table_row").path("cells");
            out.append("<thead><tr>");
            for (int c = 0; c < head.size(); c++) {
                JsonNode cell = head.get(c); // rich_text[]
                out.append("<th>")
                        .append(renderText(cell))
                        .append("</th>");
            }
            out.append("</tr></thead>");
            start = 1;
        }

        out.append("<tbody>");
        for (int i = start; i < rowBlocks.size(); i++) {
            JsonNode row = rowBlocks.get(i).path("table_row").path("cells");
            out.append("<tr>");
            for (int c = 0; c < row.size(); c++) {
                JsonNode cell = row.get(c); // rich_text[]
                boolean rowHeaderCell = hasRowHeader && c == 0;
                if (rowHeaderCell) {
                    out.append("<th scope='row'>").append(renderText(cell)).append("</th>");
                } else {
                    out.append("<td>").append(renderText(cell)).append("</td>");
                }
            }
            out.append("</tr>");
        }
        out.append("</tbody></table>");
        out.append("</div>");
    }

}
