package com.salesmanager.test.pricing;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.model.catalog.category.Category;
import com.salesmanager.core.model.catalog.category.CategoryDescription;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.attribute.ProductAttribute;
import com.salesmanager.core.model.catalog.product.attribute.ProductOption;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionDescription;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionType;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionValue;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionValueDescription;
import com.salesmanager.core.model.catalog.product.availability.ProductAvailability;
import com.salesmanager.core.model.catalog.product.description.ProductDescription;
import com.salesmanager.core.model.catalog.product.price.FinalPrice;
import com.salesmanager.core.model.catalog.product.price.ProductPrice;
import com.salesmanager.core.model.catalog.product.price.ProductPriceDescription;
import com.salesmanager.core.model.catalog.product.type.ProductType;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;

/**
 * Teste de integração da jornada "Cliente compra produto com desconto sazonal
 * e atributo (cor)". Utiliza o Spring Context de teste ({@code
 * AbstractSalesManagerCoreTestCase}), o H2 em memória e a cadeia real
 * {@code ProductService → PricingService → ProductPriceUtils}.
 *
 * <p>O teste isola-se limpando a categoria criada em {@link #cleanUp()} para
 * não interferir em outros testes desta suíte.
 */
public class DiscountedPricingIntegrationTest
        extends com.salesmanager.test.common.AbstractSalesManagerCoreTestCase {

    private static final String CATEGORY_CODE_PREFIX = "sale-shirts-";
    private static final String SKU_PREFIX = "PRIT-";

    private Category createdCategory;
    private String currentSuffix;

    private String uniqueSuffix() {
        // suffix curto (<= 4 chars) para caber nas colunas VARCHAR do schema
        return Long.toString(System.nanoTime() % 100000L, 36);
    }

    private Date daysFromToday(int deltaDays) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, deltaDays);
        return c.getTime();
    }

    private Product createDiscountedProduct(BigDecimal base, BigDecimal special,
                                            Date start, Date end,
                                            BigDecimal attributeExtra)
            throws Exception {

        MerchantStore store = merchantService.getByCode(MerchantStore.DEFAULT_STORE);
        Language en = languageService.getByCode("en");
        ProductType generalType = productTypeService.getProductType(ProductType.GENERAL_TYPE);

        this.currentSuffix = uniqueSuffix();
        String categoryCode = CATEGORY_CODE_PREFIX + currentSuffix;
        String sku = SKU_PREFIX + currentSuffix;
        String optionCode = "co-" + currentSuffix;
        String optionValueCode = "bk-" + currentSuffix;

        // Categoria
        Category shirts = new Category();
        shirts.setMerchantStore(store);
        shirts.setCode(categoryCode);
        CategoryDescription cd = new CategoryDescription();
        cd.setName("Sale Shirts");
        cd.setCategory(shirts);
        cd.setLanguage(en);
        Set<CategoryDescription> descs = new HashSet<>();
        descs.add(cd);
        shirts.setDescriptions(descs);
        categoryService.create(shirts);
        this.createdCategory = shirts;

        // Opção "color" + valor "black"
        ProductOption option = new ProductOption();
        option.setMerchantStore(store);
        option.setCode(optionCode);
        option.setProductOptionType(ProductOptionType.Radio.name());
        ProductOptionDescription od = new ProductOptionDescription();
        od.setLanguage(en);
        od.setName("Color");
        od.setDescription("Item color");
        od.setProductOption(option);
        option.getDescriptions().add(od);
        productOptionService.saveOrUpdate(option);

        ProductOptionValue black = new ProductOptionValue();
        black.setMerchantStore(store);
        black.setCode(optionValueCode);
        ProductOptionValueDescription ovd = new ProductOptionValueDescription();
        ovd.setLanguage(en);
        ovd.setName("Black");
        ovd.setProductOptionValue(black);
        black.getDescriptions().add(ovd);
        productOptionValueService.saveOrUpdate(black);

        // Produto
        Product p = new Product();
        p.setSku(sku);
        p.setType(generalType);
        p.setMerchantStore(store);
        ProductDescription pd = new ProductDescription();
        pd.setName("Integration Shirt " + currentSuffix);
        pd.setLanguage(en);
        pd.setProduct(p);
        p.getDescriptions().add(pd);
        p.getCategories().add(shirts);

        // Availability + preço com desconto sazonal
        ProductAvailability availability = new ProductAvailability();
        availability.setProductDateAvailable(new Date());
        availability.setProductQuantity(50);
        availability.setRegion("*");
        availability.setProduct(p);

        ProductPrice price = new ProductPrice();
        price.setDefaultPrice(true);
        price.setProductPriceAmount(base);
        price.setProductPriceSpecialAmount(special);
        price.setProductPriceSpecialStartDate(start);
        price.setProductPriceSpecialEndDate(end);
        price.setProductAvailability(availability);

        ProductPriceDescription ppd = new ProductPriceDescription();
        ppd.setName("Base price");
        ppd.setProductPrice(price);
        ppd.setLanguage(en);
        price.getDescriptions().add(ppd);

        availability.getPrices().add(price);
        p.getAvailabilities().add(availability);

        // Atributo (cor preta) default com preço extra
        ProductAttribute attr = new ProductAttribute();
        attr.setProduct(p);
        attr.setProductOption(option);
        attr.setProductOptionValue(black);
        attr.setAttributeDefault(true);
        attr.setProductAttributePrice(attributeExtra);
        attr.setProductAttributeWeight(BigDecimal.ZERO);
        p.getAttributes().add(attr);

        productService.saveProduct(p);
        return p;
    }

    @After
    public void cleanUp() throws ServiceException {
        if (createdCategory != null) {
            try {
                categoryService.delete(createdCategory);
            } catch (Exception ignore) {
                // limpeza best-effort
            }
        }
    }

    // -----------------------------------------------------------------
    // Casos de teste da jornada
    // -----------------------------------------------------------------

    /**
     * Cenário 1: promoção ativa dentro da janela + atributo default (cor)
     * com custo extra. Verifica que a persistência + PricingService retornam
     * FinalPrice consistente com as regras.
     */
    @Test
    public void testActiveDiscountWithAttribute_returnsDiscountedFinalPrice()
            throws Exception {

        Product created = createDiscountedProduct(
                new BigDecimal("100.00"),
                new BigDecimal("80.00"),
                daysFromToday(-2),
                daysFromToday(2),
                new BigDecimal("5.00"));
        Assert.assertNotNull("Produto deve ter sido criado", created);

        // recarrega do BD para simular fluxo real de leitura
        Product reloaded = productService.getBySku(SKU_PREFIX + currentSuffix,
                merchantService.getByCode(MerchantStore.DEFAULT_STORE),
                languageService.getByCode("en"));
        Assert.assertNotNull("Produto deve ter sido persistido", reloaded);

        FinalPrice fp = pricingService.calculateProductPrice(reloaded);

        Assert.assertNotNull(fp);
        Assert.assertTrue("Preço deve estar em promoção", fp.isDiscounted());
        // preço final = specialAmount (80) + atributo default (5) = 85
        Assert.assertEquals(0,
                new BigDecimal("85.00").compareTo(fp.getFinalPrice()));
        // preço original = amount (100) + atributo (5) = 105
        Assert.assertEquals(0,
                new BigDecimal("105.00").compareTo(fp.getOriginalPrice()));
        // desconto ≈ 20%
        Assert.assertEquals(20, fp.getDiscountPercent());
    }

    /**
     * Cenário 2: promoção expirada. Mesma configuração, mas endDate no passado
     * → não há desconto e o atributo é somado ao preço base normalmente.
     */
    @Test
    public void testExpiredDiscount_noDiscountApplied() throws Exception {
        Product product = createDiscountedProduct(
                new BigDecimal("100.00"),
                new BigDecimal("60.00"),
                daysFromToday(-10),
                daysFromToday(-1),
                new BigDecimal("5.00"));

        FinalPrice fp = pricingService.calculateProductPrice(product);

        Assert.assertFalse("Promoção expirada não deve estar ativa",
                fp.isDiscounted());
        Assert.assertEquals(0,
                new BigDecimal("105.00").compareTo(fp.getFinalPrice()));
    }

    /**
     * Cenário 3: promoção futura ainda não iniciada. Nenhum desconto aplicado.
     */
    @Test
    public void testFutureDiscount_notYetActive() throws Exception {
        Product p = createDiscountedProduct(
                new BigDecimal("100.00"),
                new BigDecimal("60.00"),
                daysFromToday(1),
                daysFromToday(10),
                BigDecimal.ZERO);

        FinalPrice fp = pricingService.calculateProductPrice(p);

        Assert.assertFalse(fp.isDiscounted());
        Assert.assertEquals(0,
                new BigDecimal("100.00").compareTo(fp.getFinalPrice()));
    }
}
