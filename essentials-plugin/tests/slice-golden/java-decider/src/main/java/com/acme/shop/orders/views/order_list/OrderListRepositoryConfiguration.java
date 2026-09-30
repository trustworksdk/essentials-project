package com.acme.shop.orders.views.order_list;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import dk.trustworks.essentials.components.document_db.postgresql.DbType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Repository wiring for THIS view slice's read model.
 *
 * NAMING — the class is {@code OrderListRepositoryConfiguration}, not {@code OrderListRepository}, and
 * that is load-bearing. Spring names a {@code @Configuration} class's own bean by decapitalising the
 * simple class name, so a class called {@code OrderListRepository} would claim the bean name
 * {@code orderListRepository} — exactly the name its {@code @Bean} method below claims. The
 * result is a {@code BeanDefinitionOverrideException} at context startup, which fails *every*
 * {@code @SpringBootTest} in the project, not just this slice. Rename the class if you must; never
 * rename it to collide with a {@code @Bean} method it declares.
 *
 * The {@code @Bean} method name is the injection name consumers see — keep it
 * {@code orderListRepository}.
 *
 * {@code createForStringId(Class)} is the Java-friendly factory overload — it takes a {@code Class}
 * rather than a Kotlin {@code KClass}. Use it when the {@code @Id} is a plain {@code String}; for a
 * non-String id use {@code createForCompositeId(Class, Function<ID, String>)}.
 *
 * Indexes are added once, at construction — never per query. {@code addIndexByPaths} takes
 * dot-notation JSON paths and needs no Kotlin property references.
 *
 * BUILD — {@code postgresql-document-db} declares {@code kotlin-stdlib-jdk8} and {@code kotlin-reflect}
 * in {@code provided} scope, so they are NOT transitive. A pure-Java module still needs both on its
 * own compile classpath: {@code createForStringId} and the {@code Condition} DSL expose
 * {@code KClass}/{@code KProperty1} overloads that javac must resolve to pick the {@code Class}-based
 * one. Without them the build fails with {@code cannot access kotlin.reflect.KClass}. Adding a view
 * slice is what pulls this module in, so declare them in the module's {@code pom.xml}.
 */
@Configuration
public class OrderListRepositoryConfiguration {

    @Bean
    public DocumentDbRepository<OrderListView, String> orderListRepository(
            DocumentDbRepositoryFactory factory) {

        var repository = factory.createForStringId(OrderListView.class);

        // TODO: add the indexes this view's queries actually need.
        repository.addIndexByPaths("orders_order_list_status", "status");

        return repository;
    }

    /**
     * Example of a slice-specific query using the Java path-string condition API.
     *
     * Pass an explicit {@link DbType} whenever the path is numeric or temporal — without it the
     * value is compared as text, so ranges and ordering are wrong.
     */
    public static List<OrderListView> findByStatus(
            DocumentDbRepository<OrderListView, String> repository, String status) {
        return repository.queryBuilder()
                .where(repository.condition().eq("status", status))
                .find();
    }
}
