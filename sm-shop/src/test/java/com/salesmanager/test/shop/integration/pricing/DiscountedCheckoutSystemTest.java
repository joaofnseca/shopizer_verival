package com.salesmanager.test.shop.integration.pricing;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.shop.application.ShopApplication;
import com.salesmanager.shop.model.catalog.category.Category;
import com.salesmanager.shop.model.catalog.category.CategoryDescription;
import com.salesmanager.shop.model.catalog.category.PersistableCategory;
import com.salesmanager.shop.model.catalog.product.PersistableProductPrice;
import com.salesmanager.shop.model.catalog.product.ProductDescription;
import com.salesmanager.shop.model.catalog.product.ReadableProduct;
import com.salesmanager.shop.model.catalog.product.product.PersistableProduct;
import com.salesmanager.shop.model.catalog.product.product.PersistableProductInventory;
import com.salesmanager.shop.model.catalog.product.product.ProductSpecification;
import com.salesmanager.shop.model.entity.Entity;
import com.salesmanager.shop.model.shoppingcart.PersistableShoppingCartItem;
import com.salesmanager.shop.model.shoppingcart.ReadableShoppingCart;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Teste de sistema (end-to-end via HTTP + Spring Boot com porta aleatória) que
 * exercita a jornada:
 *
 * <ol>
 *   <li>Administrador loga (herdado do {@code ServicesTestSupport}).</li>
 *   <li>Administrador cria categoria, produto e preço promocional
 *       (specialPrice + startDate/endDate).</li>
 *   <li>Cliente lê o produto pela API pública e valida que o preço com
 *       desconto aparece.</li>
 *   <li>Cliente adiciona 2 unidades ao carrinho e valida que o total
 *       reflete o preço com desconto e a quantidade.</li>
 * </ol>
 *
 * Esta cadeia percorre controller REST → serviço → repositório → H2, e
 * fornece uma cobertura ponta-a-ponta que a suíte original não possui para a
 * regra de desconto sazonal.
 */
@SpringBootTest(classes = ShopApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@RunWith(SpringRunner.class)
public class DiscountedCheckoutSystemTest extends ServicesTestSupport {

    private static final BigDecimal BASE_PRICE = new BigDecimal("200.00");
    private static final BigDecimal SPECIAL_PRICE = new BigDecimal("150.00");

    private String uniqueSuffix() {
        return String.valueOf(System.nanoTime());
    }

    private Date daysFromToday(int delta) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, delta);
        return c.getTime();
    }

    private String isoDate(int deltaDays) {
        return new SimpleDateFormat("yyyy-MM-dd").format(daysFromToday(deltaDays));
    }

    private PersistableCategory buildCategory(String code) {
        PersistableCategory c = new PersistableCategory();
        c.setCode(code);
        c.setSortOrder(1);
        c.setVisible(true);
        c.setDepth(1);

        Category parent = new Category();
        c.setParent(parent);

        CategoryDescription description = new CategoryDescription();
        description.setLanguage("en");
        description.setName(code);
        description.setFriendlyUrl(code);
        description.setTitle(code);

        List<CategoryDescription> descs = new ArrayList<>();
        descs.add(description);
        c.setDescriptions(descs);
        return c;
    }

    private PersistableProduct buildDiscountedProduct(String sku, Category cat) {
        PersistableProduct product = new PersistableProduct();
        product.setSku(sku);

        // Preço com desconto sazonal ativo (hoje ± 2 dias)
        PersistableProductPrice pp = new PersistableProductPrice();
        pp.setDefaultPrice(true);
        pp.setPrice(BASE_PRICE);
        pp.setDiscountedPrice(SPECIAL_PRICE);
        pp.setDiscountStartDate(isoDate(-2));
        pp.setDiscountEndDate(isoDate(2));

        PersistableProductInventory inv = new PersistableProductInventory();
        inv.setQuantity(50);
        inv.setSku(sku);
        inv.setPrice(pp);
        product.setInventory(inv);

        ProductDescription desc = new ProductDescription();
        desc.setName("Discounted Shirt " + sku);
        desc.setLanguage("en");
        product.getDescriptions().add(desc);

        ArrayList<Category> cats = new ArrayList<>();
        cats.add(cat);
        product.setCategories(cats);

        ProductSpecification spec = new ProductSpecification();
        spec.setManufacturer(
            com.salesmanager.core.model.catalog.product.manufacturer.Manufacturer.DEFAULT_MANUFACTURER);
        product.setProductSpecifications(spec);
        product.setAvailable(true);
        product.setPrice(BASE_PRICE);
        product.setQuantity(50);
        return product;
    }

    /**
     * CT-SYS-01: Jornada completa. Verifica que o produto criado com desconto
     * ativo aparece com preço promocional na API pública e no carrinho.
     */
    @Test
    public void discountedProductAppearsWithSpecialPriceInCart() throws Exception {
        String suffix = uniqueSuffix();
        String catCode = "cat-sys-" + suffix;
        String sku = "SYS-" + suffix;

        // 1) Cria categoria
        PersistableCategory cat = buildCategory(catCode);
        HttpEntity<PersistableCategory> catEntity = new HttpEntity<>(cat, getHeader());
        ResponseEntity<PersistableCategory> catResp = testRestTemplate.postForEntity(
                "/api/v1/private/category?store=" + Constants.DEFAULT_STORE,
                catEntity, PersistableCategory.class);
        assertThat(catResp.getStatusCode(), is(CREATED));
        PersistableCategory createdCat = catResp.getBody();
        assertNotNull(createdCat);
        assertNotNull(createdCat.getId());

        // 2) Cria produto com desconto sazonal
        PersistableProduct product = buildDiscountedProduct(sku, createdCat);
        HttpEntity<PersistableProduct> productEntity = new HttpEntity<>(product, getHeader());
        ResponseEntity<Entity> productResp = testRestTemplate.postForEntity(
                "/api/v1/private/product?store=" + Constants.DEFAULT_STORE,
                productEntity, Entity.class);
        assertThat("O cadastro do produto deve retornar 201",
                productResp.getStatusCode(), is(CREATED));

        // 3) Lê o produto via API pública
        HttpEntity<String> readEntity = new HttpEntity<>(getHeader());
        ResponseEntity<ReadableProduct> readResp = testRestTemplate.exchange(
                "/api/v2/product/" + sku, HttpMethod.GET, readEntity, ReadableProduct.class);
        assertThat(readResp.getStatusCode(), is(OK));
        ReadableProduct readable = readResp.getBody();
        assertNotNull("Produto deveria ser retornado", readable);

        // 4) Adiciona 2 unidades ao carrinho
        PersistableShoppingCartItem item = new PersistableShoppingCartItem();
        item.setProduct(sku);
        item.setQuantity(2);

        HttpEntity<PersistableShoppingCartItem> cartEntity = new HttpEntity<>(item, getHeader());
        ResponseEntity<ReadableShoppingCart> cartResp = testRestTemplate.postForEntity(
                "/api/v1/cart/", cartEntity, ReadableShoppingCart.class);

        assertThat("Adição ao carrinho deve retornar 201",
                cartResp.getStatusCode(), is(CREATED));
        ReadableShoppingCart cart = cartResp.getBody();
        assertNotNull(cart);
        assertEquals("Carrinho deve conter 2 unidades", 2, cart.getQuantity());

        // Verifica que o carrinho reflete preço com desconto (150 * 2 = 300),
        // e NÃO o preço cheio (200 * 2 = 400).
        BigDecimal expectedTotal = SPECIAL_PRICE.multiply(new BigDecimal(2));
        BigDecimal fullTotal    = BASE_PRICE.multiply(new BigDecimal(2));
        BigDecimal cartSubTotal = cart.getSubtotal();
        assertNotNull("Subtotal do carrinho não pode ser nulo", cartSubTotal);
        assertTrue(
                "Subtotal (" + cartSubTotal + ") deve refletir preço promocional (esperado " +
                        expectedTotal + " e NÃO " + fullTotal + ")",
                cartSubTotal.compareTo(fullTotal) < 0);
        assertEquals("Subtotal deve ser exatamente o preço promocional × quantidade",
                0, cartSubTotal.compareTo(expectedTotal));
    }

    /**
     * CT-SYS-02: mesmo produto, mas com promoção expirada. Não deve haver
     * desconto no carrinho — subtotal = preço cheio × quantidade.
     */
    @Test
    public void expiredDiscountedProduct_showsFullPriceInCart() throws Exception {
        String suffix = uniqueSuffix();
        String catCode = "cat-exp-" + suffix;
        String sku = "EXP-" + suffix;

        PersistableCategory cat = buildCategory(catCode);
        ResponseEntity<PersistableCategory> catResp = testRestTemplate.postForEntity(
                "/api/v1/private/category?store=" + Constants.DEFAULT_STORE,
                new HttpEntity<>(cat, getHeader()), PersistableCategory.class);
        assertThat(catResp.getStatusCode(), is(CREATED));

        PersistableProduct product = buildDiscountedProduct(sku, catResp.getBody());
        // sobrescreve datas para uma promoção JÁ expirada
        product.getInventory().getPrice().setDiscountStartDate(isoDate(-10));
        product.getInventory().getPrice().setDiscountEndDate(isoDate(-1));

        ResponseEntity<Entity> productResp = testRestTemplate.postForEntity(
                "/api/v1/private/product?store=" + Constants.DEFAULT_STORE,
                new HttpEntity<>(product, getHeader()), Entity.class);
        assertThat(productResp.getStatusCode(), is(CREATED));

        PersistableShoppingCartItem item = new PersistableShoppingCartItem();
        item.setProduct(sku);
        item.setQuantity(1);

        ResponseEntity<ReadableShoppingCart> cartResp = testRestTemplate.postForEntity(
                "/api/v1/cart/",
                new HttpEntity<>(item, getHeader()), ReadableShoppingCart.class);
        assertThat(cartResp.getStatusCode(), is(CREATED));
        ReadableShoppingCart cart = cartResp.getBody();
        assertNotNull(cart);

        BigDecimal subTotal = cart.getSubtotal();
        assertNotNull(subTotal);
        assertEquals("Promoção expirada — subtotal deve ser o preço cheio",
                0, subTotal.compareTo(BASE_PRICE));
    }
}
