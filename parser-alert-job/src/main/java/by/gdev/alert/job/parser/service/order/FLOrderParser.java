package by.gdev.alert.job.parser.service.order;

import by.gdev.alert.job.parser.domain.db.Category;
import by.gdev.alert.job.parser.domain.db.Order;
import by.gdev.alert.job.parser.domain.db.ParserSource;
import by.gdev.alert.job.parser.domain.db.Price;
import by.gdev.alert.job.parser.domain.db.Subcategory;
import by.gdev.alert.job.parser.domain.rss.Item;
import by.gdev.alert.job.parser.domain.rss.Rss;
import by.gdev.common.model.OrderDTO;
import by.gdev.common.model.SiteName;
import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.alert.job.parser.service.order.jsoup.JsoupClient;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.UnmarshalException;
import jakarta.xml.bind.Unmarshaller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.HttpStatusException;
import org.jsoup.select.Elements;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class FLOrderParser extends AbsctractSiteParser {

    private final JsoupClient jsoupClient;

    private Pattern paymentPatter = Pattern.compile(".*[Бб]юджет: (\\d+).*");
    private Pattern currencyPatter = Pattern.compile("\\d.*&#8381;");

    private final ModelMapper mapper;

    private static final int PROXY_RETRY_ATTEMPTS = 3;

    @Value("${flru.proxy.active:false}")
    private boolean proxyActive;

    @Value("${parser.work.fl.ru}")
    private void setActive(boolean active) {
        this.active = active;
    }

    @Override
    protected List<OrderDTO> mapItems(String rssURI, Long siteSourceJobId, Category category, Subcategory subCategory) {
		if (!active)
			return new ArrayList<>();

        if (rssURI == null || rssURI.isBlank()) {
            log.warn("{}: пустой RSS URI для категории {}", getSiteName(), category.getNativeLocName());
            return List.of();
        }

        String feedUri;
        try {
            feedUri = resolveFeedUri(rssURI);
        } catch (IOException e) {
            log.warn("{}: не удалось определить RSS для uri={}: {}", getSiteName(), rssURI, e.getMessage());
            return List.of();
        }
        if (feedUri == null || feedUri.isBlank()) {
            log.warn("{}: для uri={} не найден RSS (ожидается /rss/all.xml или link rel=alternate)", getSiteName(), rssURI);
            return List.of();
        }

        String xml;
        try {
            xml = fetchFlRuBody(feedUri);
        } catch (IOException e) {
            log.warn("{}: ошибка загрузки RSS {}: {}", getSiteName(), feedUri, e.getMessage());
            return List.of();
        }
        if (xml == null) {
            log.warn("{} вернул NULL вместо RSS, uri={}", getSiteName(), feedUri);
            return List.of();
        }
        String trimmed = xml.trim();
        if (FlRuPlaywrightGuards.isSuspiciousIpActivityInBody(trimmed)) {
            log.warn("{}: ответ похож на блок IP (подозрительная активность), uri={}", getSiteName(), feedUri);
            return List.of();
        }
        if (!looksLikeRss(trimmed)) {
            log.warn("{}: ответ не похож на RSS (HTML или другой формат), uri={}", getSiteName(), feedUri);
            return List.of();
        }

        Rss rss;
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(Rss.class);
            Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
            rss = (Rss) jaxbUnmarshaller.unmarshal(new StringReader(trimmed));
        } catch (UnmarshalException e) {
            log.warn("{}: не удалось разобрать RSS, uri={}: {}", getSiteName(), feedUri, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.warn("{}: ошибка JAXB для uri={}: {}", getSiteName(), feedUri, e.getMessage());
            return List.of();
        }

        if (rss.getChannel() == null || rss.getChannel().getItem() == null)
            return List.of();

        List<Order> rawOrders = rss.getChannel().getItem().stream()
                .map(item -> buildOrder(item, siteSourceJobId, category, subCategory))
                .filter(Objects::nonNull)
                .toList();

        return getOrdersData(rawOrders, category, subCategory);
    }

    private Order buildOrder(Item item, Long siteSourceJobId, Category category, Subcategory subCategory) {
        if (!getParserService().shouldSaveOrder(category, subCategory, item.getLink()))
            return null;

        Order order = getOrderRepository().findByLink(item.getLink()).orElse(new Order());

        order.setTitle(item.getTitle());
        order.setDateTime(item.getPubDate());
        order.setMessage(item.getDescription());
        order.setLink(item.getLink());

        order = parsePrice(order);

        String cleaned = order.getTitle().replaceAll("(\\(Бюджет: .*[0-9\\;\\)])", "");
        order.setTitle(cleaned);

        ParserSource ps = new ParserSource();
        ps.setSource(siteSourceJobId);
        ps.setCategory(category.getId());
        ps.setSubCategory(subCategory != null ? subCategory.getId() : null);
        order.setSourceSite(ps);
        return order;
    }

    private Order parsePrice(Order order) {
        Matcher m = paymentPatter.matcher(order.getTitle());
        Price price = new Price();
        if (m.find()) {
            price.setValue(Integer.valueOf(m.group(1)));
            order.setPrice(price);
        }
        Matcher m1 = currencyPatter.matcher(order.getTitle());
        if (m1.find()) {
            price.setPrice(m1.group(0).replaceAll("&#8381;", "руб."));
            order.setPrice(price);
        }

        Document doc;
        try {
            doc = fetchFlRuDocument(order.getLink());
            if (doc == null) {
                log.warn("{} вернул null (502/503/504) для {}", getSiteName(),  order.getLink());
                return order;
            }
            if (FlRuPlaywrightGuards.isSuspiciousIpActivityInBody(doc.html())) {
                log.warn("{}: блок IP при загрузке заказа {}", getSiteName(), order.getLink());
                return order;
            }
        } catch (Exception ex) {
            order.setValidOrder(false);
            log.debug("invalid flru link {}", order.getLink());
            return order;
        }

        Element el = doc.selectFirst(".b-layout__txt_lineheight_1");
        if (Objects.nonNull(el) && (el.text().contains("Срочный заказ") || el.text().contains("Для всех"))) {
            order.setOpenForAll(true);
        }
        return order;
    }

    private static boolean looksLikeRss(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("<html") || lower.contains("<!doctype html")) {
            return false;
        }
        return lower.contains("<rss");
    }

    /**
     * Прямой RSS или ссылка из HTML-страницы специализации (/freelancers/...).
     */
    private String resolveFeedUri(String uri) throws IOException {
        if (uri.contains("/rss/")) {
            return uri;
        }
        if (!uri.contains("/freelancers/")) {
            return uri;
        }
        String html = fetchFlRuBody(uri);
        if (html == null || html.isBlank()) {
            return null;
        }
        Document doc = Jsoup.parse(html, uri);
        Element rssLink = doc.selectFirst("link[type=application/rss+xml][href]");
        if (rssLink != null) {
            String href = rssLink.attr("abs:href");
            if (!href.isBlank()) {
                log.debug("{}: RSS из link rel=alternate: {}", getSiteName(), href);
                return href;
            }
        }
        Elements candidates = doc.select("a[href*=/rss/]");
        for (Element a : candidates) {
            String href = a.attr("abs:href");
            if (href.contains("/rss/all.xml")) {
                log.debug("{}: RSS из ссылки на странице: {}", getSiteName(), href);
                return href;
            }
        }
        return null;
    }

    /**
     * Загрузка через прокси при блокировке IP или если {@code flru.proxy.active=true}.
     */
    private String fetchFlRuBody(String url) throws IOException {
        String body;
        if (!proxyActive) {
            body = jsoupClient.getRaw(url);
            if (body != null && !FlRuPlaywrightGuards.isSuspiciousIpActivityInBody(body)) {
                return decodeFlRuRssBody(body, url);
            }
            log.debug("{}: прямой запрос заблокирован или пустой, пробуем прокси: {}", getSiteName(), url);
            body = fetchFlRuBodyViaProxy(url);
        } else {
            body = fetchFlRuBodyViaProxy(url);
        }
        return decodeFlRuRssBody(body, url);
    }

    private String fetchFlRuBodyViaProxy(String url) throws IOException {
        String lastBody = null;
        for (int attempt = 1; attempt <= PROXY_RETRY_ATTEMPTS; attempt++) {
            ProxyCredentials proxy = jsoupClient.getProxyWithRetry(3, 1500);
            if (proxy == null) {
                log.warn("{}: нет активного прокси (попытка {}/{}), url={}", getSiteName(), attempt, PROXY_RETRY_ATTEMPTS, url);
                break;
            }
            lastBody = jsoupClient.getRaw(url, proxy);
            if (lastBody != null && !FlRuPlaywrightGuards.isSuspiciousIpActivityInBody(lastBody)) {
                return lastBody;
            }
            log.debug("{}: прокси {}:{} — блок IP или пустой ответ (попытка {}/{})",
                    getSiteName(), proxy.getHost(), proxy.getPort(), attempt, PROXY_RETRY_ATTEMPTS);
        }
        return lastBody;
    }

    /**
     * RSS FL.ru через прокси: HttpClient отдаёт UTF-8 как ISO-8859-1 → «Ð°Ð³ÐµÐ½Ñ» в title.
     */
    private static String decodeFlRuRssBody(String body, String url) {
        if (body == null || body.isBlank()) {
            return body;
        }
        if (!isFlRuRssPayload(body, url)) {
            return body;
        }
        if (!looksLikeUtf8MisreadAsLatin1(body)) {
            return body;
        }
        String fixed = new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return looksLikeRss(fixed) ? fixed : body;
    }

    private static boolean isFlRuRssPayload(String body, String url) {
        if (url != null && url.contains("/rss/")) {
            return true;
        }
        String lower = body.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("<?xml") || lower.contains("<rss");
    }

    private static boolean looksLikeUtf8MisreadAsLatin1(String text) {
        return text.indexOf('Ð') >= 0 || text.indexOf('Ñ') >= 0 || text.indexOf('\u00c2') >= 0;
    }

    private Document fetchFlRuDocument(String url) throws IOException {
        if (!proxyActive) {
            try {
                Document direct = jsoupClient.get(url);
                if (direct != null && !FlRuPlaywrightGuards.isSuspiciousIpActivityInBody(direct.html())) {
                    return direct;
                }
            } catch (HttpStatusException ignored) {
                // ниже — прокси
            }
        }
        for (int attempt = 1; attempt <= PROXY_RETRY_ATTEMPTS; attempt++) {
            ProxyCredentials proxy = jsoupClient.getProxyWithRetry(3, 1500);
            if (proxy == null) {
                break;
            }
            Document doc = jsoupClient.get(url, proxy);
            if (doc != null && !FlRuPlaywrightGuards.isSuspiciousIpActivityInBody(doc.html())) {
                return doc;
            }
        }
        return jsoupClient.get(url);
    }


    public SiteName getSiteName() {
        return SiteName.FLRU;
    }
}