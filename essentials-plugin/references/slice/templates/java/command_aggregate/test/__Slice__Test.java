package {{packagePath}}.{{bc}}.use_cases.{{slice}};

import {{packagePath}}.{{bc}}.aggregates.{{Aggregate}};
import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for THIS slice's decision — which on the aggregate lane means testing the
 * {@link {{Aggregate}}} method, not the handler.
 *
 * No Spring, no database, no mocks: construct the aggregate, call the method, assert on the events
 * it applied. Testing the handler here would test delegation, which is the one thing on this lane
 * that cannot be wrong in an interesting way.
 */
class {{Slice}}Test {

    @Test
    void applies_the_event_when_the_state_changes() {
        var {{aggregate}} = new {{Aggregate}}({{Aggregate}}Id.random(), "initial");

        var changed = {{aggregate}}.applyPlaceholder("updated");

        assertThat(changed).isTrue();
        assertThat({{aggregate}}.placeholder()).isEqualTo("updated");
    }

    @Test
    void is_a_no_op_when_the_state_was_already_reached() {
        var {{aggregate}} = new {{Aggregate}}({{Aggregate}}Id.random(), "initial");

        var changed = {{aggregate}}.applyPlaceholder("initial");

        // The command bus delivers at least once — a redelivery must append no second event.
        assertThat(changed).isFalse();
    }

    @Test
    void rejects_before_applying_anything() {
        var {{aggregate}} = new {{Aggregate}}({{Aggregate}}Id.random(), "initial");

        // TODO: replace with this slice's real invariant. The point of the assertion is that the
        //       guard runs BEFORE apply(), so a rejected command leaves the aggregate untouched.
        assertThatThrownBy(() -> {{aggregate}}.applyPlaceholder(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat({{aggregate}}.placeholder()).isEqualTo("initial");
    }
}
