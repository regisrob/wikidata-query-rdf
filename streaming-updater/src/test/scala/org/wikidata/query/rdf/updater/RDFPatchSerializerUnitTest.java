package org.wikidata.query.rdf.updater;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.wikidata.query.rdf.test.StatementHelper.statements;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.junit.Before;
import org.junit.Test;
import org.wikidata.query.rdf.tool.rdf.Patch;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

public class RDFPatchSerializerUnitTest {
    private final Kryo kryo = new Kryo();

    @Before
    public void setup() {
        kryo.register(Patch.class, new RDFPatchSerializer());
    }

    @Test
    public void testSerDeser() {
        Patch p = new Patch(statements("uri:added"), statements("uri:linked"),
                statements("uri:deleted"), statements("uri:unlinked"));
        assertThat(readSerializedBytes(getSerializedBytes(p))).isEqualTo(p);
    }

    @Test
    public void testNullAndEmpty() {
        Patch p = new Patch(null, emptyList(), null, emptyList());
        assertThat(readSerializedBytes(getSerializedBytes(p))).isEqualTo(p);
        p = new Patch(emptyList(), null, emptyList(), null);
        assertThat(readSerializedBytes(getSerializedBytes(p))).isEqualTo(p);
    }

    private byte[] getSerializedBytes(Patch p) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (Output output = new Output(baos)) {
            kryo.writeObject(output, p);
        }
        return baos.toByteArray();
    }

    private Patch readSerializedBytes(byte[] bytes) {
        return kryo.readObject(new Input(new ByteArrayInputStream(bytes)), Patch.class);
    }
}
