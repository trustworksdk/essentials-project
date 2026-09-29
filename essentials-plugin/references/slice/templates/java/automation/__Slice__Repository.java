package {{packagePath}}.{{bc}}.automations.{{slice}};

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Persistence for this automation's process state. Owned by THIS slice — no other slice reads it.
 *
 * Delete this file (and the TodoList) if the automation is stateless.
 *
 * NAMING — this {@code @Configuration} class registers a bean under its own decapitalised name
 * ({@code {{sliceCamel}}Repository}), which is why the {@code @Bean} method below is
 * {@code {{sliceCamel}}TodoRepository} and not {@code {{sliceCamel}}Repository}. Renaming the method
 * to match the class produces a {@code BeanDefinitionOverrideException} at context startup that
 * fails every {@code @SpringBootTest} in the project. Keep the two names distinct.
 */
@Configuration
public class {{Slice}}Repository {

    @Bean
    public DocumentDbRepository<{{Slice}}TodoList, String> {{sliceCamel}}TodoRepository(
            DocumentDbRepositoryFactory factory) {
        return factory.createForStringId({{Slice}}TodoList.class);
    }
}
