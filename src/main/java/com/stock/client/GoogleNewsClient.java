package com.stock.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class GoogleNewsClient {

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final DateTimeFormatter RFC822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    private final WebClient client = WebClient.builder()
            .baseUrl("https://news.google.com")
            .defaultHeader("User-Agent", UA)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();

    public List<Article> search(String query, int max) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            byte[] body = client.get()
                    .uri("/rss/search?q=" + encoded + "&hl=ko&gl=KR&ceid=KR:ko")
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            return parseRss(body, max);
        } catch (Exception e) {
            log.warn("[GoogleNews] '{}' 실패: {}", query, e.getMessage());
            return List.of();
        }
    }

    private List<Article> parseRss(byte[] xml, int max) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml));
        NodeList items = doc.getElementsByTagName("item");

        List<Article> result = new ArrayList<>();
        int count = Math.min(items.getLength(), max);
        for (int i = 0; i < count; i++) {
            Element item = (Element) items.item(i);
            String rawTitle = text(item, "title");
            String link = text(item, "link");
            String pubDate = text(item, "pubDate");
            String source = text(item, "source");
            String description = text(item, "description");

            String[] titleAndSource = splitTitleSource(rawTitle);
            LocalDateTime published = parseDate(pubDate);

            result.add(new Article(
                    titleAndSource[0],
                    link,
                    source.isEmpty() ? titleAndSource[1] : source,
                    stripHtml(description),
                    published
            ));
        }
        return result;
    }

    private String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent() == null ? "" : nl.item(0).getTextContent().trim();
    }

    private String[] splitTitleSource(String title) {
        int idx = title.lastIndexOf(" - ");
        if (idx > 0 && idx < title.length() - 3) {
            return new String[]{title.substring(0, idx), title.substring(idx + 3)};
        }
        return new String[]{title, ""};
    }

    private String stripHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        String noTags = html.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        return noTags.length() > 280 ? noTags.substring(0, 280) + "…" : noTags;
    }

    private LocalDateTime parseDate(String s) {
        try {
            ZonedDateTime z = ZonedDateTime.parse(s, RFC822);
            return z.withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    public record Article(String title, String url, String source, String summary, LocalDateTime publishedAt) {}
}
