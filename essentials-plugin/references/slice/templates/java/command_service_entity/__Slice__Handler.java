package {{packagePath}}.{{bc}}.use_cases.{{slice}};

import {{packagePath}}.{{bc}}.entities.{{Entity}};
import {{packagePath}}.{{bc}}.entities.{{Entity}}Repository;
import {{packagePath}}.{{bc}}.events.{{Event}};
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;

/**
 * The decision component for THIS slice (rules/slice-design.md §R1).
 *
 * ONE {@code @CmdHandler} METHOD, ONE COMMAND TYPE. A handler class carrying methods for two or more
 * command types is R1's router wearing a handler's clothes — it splits into that many slices. Adding
 * a command means adding a directory, never a method here.
 *
 * The shape is always the same four steps: **load, call the one invariant method, save, publish.**
 * The decision itself belongs on the entity, not here — this class holds no business rule. If you
 * find yourself writing an {@code if} about domain state in this file, it belongs on {@link {{Entity}}}
 * (§ The entity's own bar).
 *
 * WIRING — there is normally nothing to write. {@code ReactiveHandlersBeanPostProcessor}
 * auto-registers any {@code CommandHandler} bean with the single {@code CommandBus} bean, so
 * {@code @Component} plus a scanned package is the whole of it. The obligation is a *check*, not an
 * edit: confirm {@code reactive-bean-post-processor-enabled} (default {@code true}) has not been
 * switched off, because disabling it silently unwires every handler in the application
 * (§ Wiring is part of done).
 *
 * TRANSACTION — no {@code @Transactional} here. The {@code CommandBus} already hands the command to
 * this handler inside a unit of work (the starter's {@code UnitOfWorkControllingCommandBusInterceptor}),
 * and that unit of work is a Spring-managed transaction the repository joins, so the load, the
 * mutation, the save and the publish run in one. {@code @Transactional} would make Spring proxy this
 * class, and {@code AnnotatedCommandHandler.handle} is final: the bus would call it on the proxy, whose
 * fields are never initialised, and every command would fail with a {@code NullPointerException}.
 */
@Component
public class {{Slice}}Handler extends AnnotatedCommandHandler {

    private final {{Entity}}Repository {{entity}}s;
    private final EventBus eventBus;

    public {{Slice}}Handler({{Entity}}Repository {{entity}}s, EventBus eventBus) {
        this.{{entity}}s = {{entity}}s;
        this.eventBus = eventBus;
    }

    @CmdHandler
    public void handle({{Command}} cmd) {
        var {{entity}} = {{entity}}s.findById(cmd.id().toString())
                .orElseThrow(() -> new IllegalArgumentException("No {{Entity}} " + cmd.id().toString()));

        // TODO: call the ONE invariant method this slice's intent maps to. The boolean-returning
        //       shape below is the idempotent form — it returns false when the state was already
        //       reached, so a redelivered command is a no-op rather than a duplicate event.
        if (!{{entity}}.applyPlaceholder(cmd.placeholder())) {
            return;
        }

        {{entity}}s.save({{entity}});
        eventBus.publish(new {{Event}}(cmd.id(), cmd.placeholder()));
    }
}
