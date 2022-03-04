package no.cantara.saga.serialization;

import no.cantara.saga.api.Saga;
import org.json.JSONObject;
import org.testng.annotations.Test;

public class SagaSerializerTest {

    @Test
    public void thatEmptySagaIsValidJson() {
        Saga emptySaga = Saga.start("Empty saga").linkToEnd().end();
        String compactJson = SagaSerializer.toJson(emptySaga);
        new JSONObject(compactJson).toString(2);
    }

    @Test
    public void thatSimpleParallelSagaIsValidJson() {
        Saga parallelSaga = Saga
                .start("Parallel saga").linkTo("p1", "p2", "p3")
                .id("p1").adapter("something").linkToEnd()
                .id("p2").adapter("anything").linkToEnd()
                .id("p3").adapter("that").linkToEnd()
                .end();
        String compactJson = SagaSerializer.toJson(parallelSaga);
        new JSONObject(compactJson).toString(2);
    }

    @Test
    public void thatComplexSagaIsValidJson() {
        Saga complexSaga = Saga
                .start("Complex saga").linkTo("c1", "c2", "c3")
                .id("c1").adapter("something").linkToEnd()
                .id("c2").adapter("anything").linkTo("c7")
                .id("c3").adapter("that").linkTo("c4")
                .id("c4").adapter("complex-branch").linkTo("c5", "c6")
                .id("c5").adapter("sub_route_A").linkTo("c7")
                .id("c6").adapter("sub_route_B").linkTo("c7")
                .id("c7").adapter("Aggregate_A,_B_and_anything").linkToEnd()
                .end();
        String compactJson = SagaSerializer.toJson(complexSaga);
        new JSONObject(compactJson).toString(2);
    }
}
