package com.homehealth.scene

import com.google.android.filament.*
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

private val R = (sqrt(2.0) / 2.0).toFloat()

// Per-face tangent quaternions (x,y,z,w) — encodes TBN frame required by Filament's PBR shader.
private val FACE_TANGENTS = floatArrayOf(
    // Front  (+Z)  identity    (0,0,0,1)
    0f, 0f, 0f, 1f,   0f, 0f, 0f, 1f,   0f, 0f, 0f, 1f,   0f, 0f, 0f, 1f,
    // Back   (-Z)  180° Y      (0,1,0,0)
    0f, 1f, 0f, 0f,   0f, 1f, 0f, 0f,   0f, 1f, 0f, 0f,   0f, 1f, 0f, 0f,
    // Left   (-X)  −90° Y      (0,−R,0,R)
    0f,-R,  0f, R,    0f,-R,  0f, R,    0f,-R,  0f, R,    0f,-R,  0f, R,
    // Right  (+X)  +90° Y      (0, R,0,R)
    0f, R,  0f, R,    0f, R,  0f, R,    0f, R,  0f, R,    0f, R,  0f, R,
    // Top    (+Y)  −90° X      (−R,0,0,R)
   -R,  0f, 0f, R,   -R,  0f, 0f, R,   -R,  0f, 0f, R,   -R,  0f, 0f, R,
    // Bottom (−Y)  +90° X      ( R,0,0,R)
    R,  0f, 0f, R,    R,  0f, 0f, R,    R,  0f, 0f, R,    R,  0f, 0f, R
)

private val FACE_INDICES = shortArrayOf(
     0, 1, 2,  0, 2, 3,
     4, 5, 6,  4, 6, 7,
     8, 9,10,  8,10,11,
    12,13,14, 12,14,15,
    16,17,18, 16,18,19,
    20,21,22, 20,22,23
)

// Node(engine, entity) requires the entity to be pre-created by the caller.
// BoxNode owns vb, ib, and materialInstance and destroys all three on destroy().
class BoxNode private constructor(
    engine: Engine,
    entity: Int,
    private val vb: VertexBuffer,
    private val ib: IndexBuffer,
    private val materialInstance: MaterialInstance,
    box: Box
) : Node(engine, entity) {

    init {
        RenderableManager.Builder(1)
            .boundingBox(box)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, 36)
            .material(0, materialInstance)
            .build(engine, entity)
    }

    override fun destroy() {
        engine.destroyVertexBuffer(vb)
        engine.destroyIndexBuffer(ib)
        engine.destroyMaterialInstance(materialInstance)
        super.destroy()
    }

    companion object {
        operator fun invoke(
            engine: Engine,
            size: Size,
            materialInstance: MaterialInstance
        ): BoxNode {
            val hw = size.x / 2f
            val hh = size.y / 2f
            val hd = size.z / 2f

            val positions = floatArrayOf(
                -hw,-hh, hd,   hw,-hh, hd,   hw, hh, hd,  -hw, hh, hd,
                 hw,-hh,-hd,  -hw,-hh,-hd,  -hw, hh,-hd,   hw, hh,-hd,
                -hw,-hh,-hd,  -hw,-hh, hd,  -hw, hh, hd,  -hw, hh,-hd,
                 hw,-hh, hd,   hw,-hh,-hd,   hw, hh,-hd,   hw, hh, hd,
                -hw, hh, hd,   hw, hh, hd,   hw, hh,-hd,  -hw, hh,-hd,
                -hw,-hh,-hd,   hw,-hh,-hd,   hw,-hh, hd,  -hw,-hh, hd
            )

            val vb = VertexBuffer.Builder()
                .vertexCount(24)
                .bufferCount(2)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
                .build(engine)
            vb.setBufferAt(engine, 0, buf(positions.size * 4).also { it.asFloatBuffer().put(positions) })
            vb.setBufferAt(engine, 1, buf(FACE_TANGENTS.size * 4).also { it.asFloatBuffer().put(FACE_TANGENTS) })

            val ib = IndexBuffer.Builder()
                .indexCount(36)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine)
            ib.setBuffer(engine, buf(FACE_INDICES.size * 2).also { it.asShortBuffer().put(FACE_INDICES) })

            val entity = EntityManager.get().create()
            return BoxNode(engine, entity, vb, ib, materialInstance, Box(0f, 0f, 0f, hw, hh, hd))
        }

        private fun buf(bytes: Int) =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
