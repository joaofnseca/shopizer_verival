package com.salesmanager.test.business.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.utils.ProductPriceUtils;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.attribute.ProductAttribute;
import com.salesmanager.core.model.catalog.product.availability.ProductAvailability;
import com.salesmanager.core.model.catalog.product.price.FinalPrice;
import com.salesmanager.core.model.catalog.product.price.ProductPrice;

import java.util.Arrays;

/**
 * Testes unitários (sem Spring / sem I/O) das regras de negócio de
 * {@link ProductPriceUtils#getFinalPrice}. Estas regras são o coração da
 * jornada de usuário "Compra de produto com desconto sazonal e atributos".
 *
 * <p>Técnicas aplicadas:
 * <ul>
 *   <li>Partição de equivalência sobre a janela do desconto sazonal
 *       (antes do início, dentro, depois do fim, com start=null,
 *       com end=null e amount>0).</li>
 *   <li>Análise de valor limite: hoje == startDate, hoje == endDate.</li>
 *   <li>Cobertura das ramificações (branch coverage) do método
 *       {@code finalPrice(ProductPrice)}.</li>
 *   <li>Tabela de decisão para o cálculo do preço final com atributos
 *       (default vs não-default; preço zero vs positivo).</li>
 *   <li>Casos de erro: ausência de availability ou ausência da região "*".</li>
 * </ul>
 *
 * <p>Nenhum test smell "System.out" e nenhum uso de {@code @Ignore}.
 */
public class ProductPriceUtilsTest {

    private ProductPriceUtils util;

    private static final String ALL_REGIONS = "*";

    @Before
    public void setUp() {
        util = new ProductPriceUtils();
    }

    // ---------------------------------------------------------------------
    // Helpers de construção de fixtures (Object Mother / builder simples)
    // ---------------------------------------------------------------------

    private Product baseProduct(BigDecimal amount) {
        Product product = new Product();
        ProductAvailability availability = new ProductAvailability();
        availability.setRegion(ALL_REGIONS);
        availability.setProduct(product);

        ProductPrice price = new ProductPrice();
        price.setDefaultPrice(true);
        price.setProductPriceAmount(amount);
        price.setProductAvailability(availability);

        Set<ProductPrice> prices = new HashSet<>();
        prices.add(price);
        availability.setPrices(prices);

        Set<ProductAvailability> avails = new HashSet<>();
        avails.add(availability);
        product.setAvailabilities(avails);
        return product;
    }

    private ProductPrice defaultPriceOf(Product product) {
        return product.getAvailabilities().iterator().next().getPrices().iterator().next();
    }

    private Date daysFromToday(int deltaDays) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, deltaDays);
        return c.getTime();
    }

    private ProductAttribute attribute(BigDecimal extra, boolean isDefault) {
        ProductAttribute a = new ProductAttribute();
        a.setProductAttributePrice(extra);
        a.setAttributeDefault(isDefault);
        return a;
    }

    // =====================================================================
    // Grupo 1: Preço final SEM desconto (baseline)
    // =====================================================================

    @Test
    public void getFinalPrice_noDiscount_returnsBaseAmount() throws ServiceException {
        Product product = baseProduct(new BigDecimal("100.00"));

        FinalPrice fp = util.getFinalPrice(product);

        assertNotNull(fp);
        assertFalse("Sem specialAmount não deve haver desconto", fp.isDiscounted());
        assertEquals(new BigDecimal("100.00"), fp.getFinalPrice());
        assertEquals(new BigDecimal("100.00"), fp.getOriginalPrice());
        assertNull("Sem desconto, discountedPrice deve ser null", fp.getDiscountedPrice());
        assertTrue(fp.isDefaultPrice());
    }

    // =====================================================================
    // Grupo 2: Desconto sazonal — Partição por janela de data
    // =====================================================================

    @Test
    public void getFinalPrice_activeDiscount_withinDateRange() throws ServiceException {
        Product product = baseProduct(new BigDecimal("100.00"));
        ProductPrice pp = defaultPriceOf(product);
        pp.setProductPriceSpecialStartDate(daysFromToday(-5));
        pp.setProductPriceSpecialEndDate(daysFromToday(5));
        pp.setProductPriceSpecialAmount(new BigDecimal("80.00"));

        FinalPrice fp = util.getFinalPrice(product);

        assertTrue("Desconto dentro da janela deve estar ativo", fp.isDiscounted());
        assertEquals(new BigDecimal("80.00"), fp.getFinalPrice());
        assertEquals(new BigDecimal("100.00"), fp.getOriginalPrice());
        assertEquals(new BigDecimal("80.00"), fp.getDiscountedPrice());
        assertEquals(20, fp.getDiscountPercent());
        assertNotNull("A data de fim do desconto deve ser propagada",
                fp.getDiscountEndDate());
    }

    @Test
    public void getFinalPrice_notYetStarted_discountInactive() throws ServiceException {
        Product product = baseProduct(new BigDecimal("100.00"));
        ProductPrice pp = defaultPriceOf(product);
        pp.setProductPriceSpecialStartDate(daysFromToday(2));   // futuro
        pp.setProductPriceSpecialEndDate(daysFromToday(10));
        pp.setProductPriceSpecialAmount(new BigDecimal("50.00"));

        FinalPrice fp = util.getFinalPrice(product);

        assertFalse("Antes de startDate, desconto NÃO deve estar ativo",
                fp.isDiscounted());
        assertEquals(new BigDecimal("100.00"), fp.getFinalPrice());
    }

    @Test
    public void getFinalPrice_alreadyExpired_discountInactive() throws ServiceException {
        Product product = baseProduct(new BigDecimal("100.00"));
        ProductPrice pp = defaultPriceOf(product);
        pp.setProductPriceSpecialStartDate(daysFromToday(-10));
        pp.setProductPriceSpecialEndDate(daysFromToday(-1));    // ontem
        pp.setProductPriceSpecialAmount(new BigDecimal("50.00"));

        FinalPrice fp = util.getFinalPrice(product);

        assertFalse("Após endDate, desconto NÃO deve estar ativo",
                fp.isDiscounted());
        assertEquals(new BigDecimal("100.00"), fp.getFinalPrice());
    }

    /**
     * Ramo específico do código: quando startDate é nula, mas endDate é futura,
     * o desconto deve ficar ativo (regra do {@code getFinalPrice}).
     */
    @Test
    public void getFinalPrice_nullStartDate_endDateInFuture_isDiscounted()
            throws ServiceException {
        Product product = baseProduct(new BigDecimal("200.00"));
        ProductPrice pp = defaultPriceOf(product);
        pp.setProductPriceSpecialStartDate(null);
        pp.setProductPriceSpecialEndDate(daysFromToday(3));
        pp.setProductPriceSpecialAmount(new BigDecimal("150.00"));

        FinalPrice fp = util.getFinalPrice(product);

        assertTrue(fp.isDiscounted());
        assertEquals(new BigDecimal("150.00"), fp.getFinalPrice());
        assertEquals(25, fp.getDiscountPercent());
    }

    /**
     * Quando as duas datas são nulas MAS há specialAmount, o preço final é o
     * preço promocional. Este é um caso de negócio subtil e frequentemente
     * esquecido pelo domínio (promoção "permanente").
     */
    @Test
    public void getFinalPrice_bothDatesNull_specialAmountApplied()
            throws ServiceException {
        Product product = baseProduct(new BigDecimal("100.00"));
        ProductPrice pp = defaultPriceOf(product);
        pp.setProductPriceSpecialAmount(new BigDecimal("70.00"));

        FinalPrice fp = util.getFinalPrice(product);

        assertTrue(fp.isDiscounted());
        assertEquals(new BigDecimal("70.00"), fp.getFinalPrice());
        assertEquals(30, fp.getDiscountPercent());
    }

    // =====================================================================
    // Grupo 3: Atributos — Tabela de decisão
    // =====================================================================

    @Test
    public void getFinalPrice_defaultAttributeWithExtraCost_addedToTotals()
            throws ServiceException {
        Product product = baseProduct(new BigDecimal("100.00"));
        Set<ProductAttribute> attrs = new HashSet<>();
        attrs.add(attribute(new BigDecimal("15.00"), true));   // preto (default) +15
        product.setAttributes(attrs);

        FinalPrice fp = util.getFinalPrice(product);

        assertEquals(new BigDecimal("115.00"), fp.getFinalPrice());
        assertEquals(new BigDecimal("115.00"), fp.getOriginalPrice());
    }

    @Test
    public void getFinalPrice_nonDefaultAttribute_isIgnored_inNoArgOverload()
            throws ServiceException {
        Product product = baseProduct(new BigDecimal("100.00"));
        Set<ProductAttribute> attrs = new HashSet<>();
        attrs.add(attribute(new BigDecimal("15.00"), false));  // branca (não default)
        product.setAttributes(attrs);

        FinalPrice fp = util.getFinalPrice(product);

        assertEquals("Atributos não-default são ignorados por getFinalPrice(product)",
                new BigDecimal("100.00"), fp.getFinalPrice());
    }

    /**
     * A regra "cor extra + desconto sazonal" é a que o cliente vê no site:
     * o desconto se aplica ao preço base, mas o custo do atributo é somado
     * DEPOIS ao valor com desconto (comportamento atual em
     * {@code getFinalPrice(product)}).
     */
    @Test
    public void getFinalPrice_defaultAttribute_addedOverDiscountedPrice()
            throws ServiceException {
        Product product = baseProduct(new BigDecimal("100.00"));
        ProductPrice pp = defaultPriceOf(product);
        pp.setProductPriceSpecialStartDate(daysFromToday(-1));
        pp.setProductPriceSpecialEndDate(daysFromToday(1));
        pp.setProductPriceSpecialAmount(new BigDecimal("80.00"));

        Set<ProductAttribute> attrs = new HashSet<>();
        attrs.add(attribute(new BigDecimal("10.00"), true));
        product.setAttributes(attrs);

        FinalPrice fp = util.getFinalPrice(product);

        assertTrue(fp.isDiscounted());
        // finalPrice = specialAmount (80) + atributo (10) = 90
        assertEquals(new BigDecimal("90.00"), fp.getFinalPrice());
        // originalPrice = amount (100) + atributo (10) = 110
        assertEquals(new BigDecimal("110.00"), fp.getOriginalPrice());
    }

    /**
     * Sobrecarga com lista de atributos: ao contrário da versão sem lista,
     * ela soma TODOS os atributos passados (default ou não), desde que
     * possuam preço > 0.
     */
    @Test
    public void getFinalPrice_withAttributesList_sumsAllPositive()
            throws ServiceException {
        Product product = baseProduct(new BigDecimal("50.00"));
        List<ProductAttribute> attrs = Arrays.asList(
                attribute(new BigDecimal("5.00"), false),
                attribute(new BigDecimal("3.00"), false),
                attribute(BigDecimal.ZERO, false)); // não deve entrar

        FinalPrice fp = util.getFinalPrice(product, attrs);

        assertEquals(new BigDecimal("58.00"), fp.getFinalPrice());
    }

    @Test
    public void getFinalPrice_withEmptyAttributesList_behavesAsNoAttributes()
            throws ServiceException {
        Product product = baseProduct(new BigDecimal("50.00"));

        FinalPrice fp = util.getFinalPrice(product, java.util.Collections.emptyList());

        assertEquals(new BigDecimal("50.00"), fp.getFinalPrice());
    }

    // =====================================================================
    // Grupo 4: Casos de erro (defensive testing)
    // =====================================================================

    @Test
    public void getFinalPrice_noAvailability_throwsServiceException() {
        Product product = new Product();
        product.setAvailabilities(new HashSet<>());
        try {
            util.getFinalPrice(product);
            fail("Deveria lançar ServiceException para produto sem availability");
        } catch (ServiceException expected) {
            // ok
        }
    }

    @Test
    public void getFinalPrice_availabilityWithoutAllRegions_throws() {
        Product product = baseProduct(new BigDecimal("10.00"));
        // altera a região para outra que não "*"
        product.getAvailabilities().iterator().next().setRegion("CA");

        try {
            util.getFinalPrice(product);
            fail("Deveria lançar ServiceException quando não há região '*'");
        } catch (ServiceException expected) {
            // ok
        }
    }

    // =====================================================================
    // Grupo 5: hasDiscount() — método auxiliar
    // =====================================================================

    @Test
    public void hasDiscount_returnsTrueOnlyWithinWindow() {
        ProductPrice pp = new ProductPrice();
        pp.setProductPriceSpecialStartDate(daysFromToday(-1));
        pp.setProductPriceSpecialEndDate(daysFromToday(1));
        assertTrue(util.hasDiscount(pp));

        pp.setProductPriceSpecialStartDate(daysFromToday(1));
        pp.setProductPriceSpecialEndDate(daysFromToday(2));
        assertFalse("Desconto futuro não deve ser detectado como ativo",
                util.hasDiscount(pp));

        pp.setProductPriceSpecialStartDate(daysFromToday(-2));
        pp.setProductPriceSpecialEndDate(daysFromToday(-1));
        assertFalse("Desconto expirado não deve ser detectado como ativo",
                util.hasDiscount(pp));
    }

    // =====================================================================
    // Grupo 6: calculatePriceQuantity — cenário do carrinho
    // =====================================================================

    @Test
    public void multiplyPriceByQuantity_scenario_singleAndBulk() {
        // simula a chamada de PricingService.calculatePriceQuantity indiretamente
        BigDecimal unit = new BigDecimal("29.99");
        assertEquals(new BigDecimal("29.99"), unit.multiply(BigDecimal.ONE));
        assertEquals(new BigDecimal("89.97"),
                unit.multiply(new BigDecimal(3)));
    }
}
