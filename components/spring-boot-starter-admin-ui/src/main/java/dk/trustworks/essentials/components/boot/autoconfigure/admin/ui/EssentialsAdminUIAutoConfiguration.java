package dk.trustworks.essentials.components.boot.autoconfigure.admin.ui;

import com.vaadin.flow.spring.annotation.EnableVaadin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@EnableVaadin("dk.trustworks.essentials.ui")
@ComponentScan("dk.trustworks.essentials.ui")
public class EssentialsAdminUIAutoConfiguration {

}
