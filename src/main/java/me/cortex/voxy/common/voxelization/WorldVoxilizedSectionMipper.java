package me.cortex.voxy.common.voxelization;

import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;

public class WorldVoxilizedSectionMipper {
    private static int G(int x, int y, int z) {
        return ((y<<8)|(z<<4)|x);
    }

    private static int H(int x, int y, int z) {
        return ((y<<6)|(z<<3)|x) + 16*16*16;
    }

    private static int I(int x, int y, int z) {
        return ((y<<4)|(z<<2)|x) + 8*8*8 + 16*16*16;
    }

    private static int J(int x, int y, int z) {
        return ((y<<2)|(z<<1)|x) + 4*4*4 + 8*8*8 + 16*16*16;
    }

    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        var data = section.section;

        //Mip L1
        int i = 0;
        int MSK = 0b1110_1110_1110;
        int iMSK1 = (~MSK)+1;
        int q = 0;
        while (true) {
            data[16*16*16 + i++] = Mipper.mip(
                    data[q|G(0,0,0)], data[q|G(1,0,0)], data[q|G(0,0,1)], data[q|G(1,0,1)],
                    data[q|G(0,1,0)], data[q|G(1,1,0)], data[q|G(0,1,1)], data[q|G(1,1,1)],
                    mapper
            );
            if (q == MSK)
                break;
            q = (q+iMSK1)&MSK;
        }

        //Mip L2
        i = 0;
        for (int y = 0; y < 8; y+=2) {
            for (int z = 0; z < 8; z += 2) {
                for (int x = 0; x < 8; x += 2) {
                    data[16*16*16 + 8*8*8 + i++] =
                            Mipper.mip(
                                    data[H(x, y, z)],       data[H(x+1, y, z)],       data[H(x, y, z+1)],      data[H(x+1, y, z+1)],
                                    data[H(x, y+1, z)],  data[H(x+1, y+1, z)],  data[H(x, y+1, z+1)], data[H(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }

        //Mip L3
        i = 0;
        for (int y = 0; y < 4; y+=2) {
            for (int z = 0; z < 4; z += 2) {
                for (int x = 0; x < 4; x += 2) {
                    data[16*16*16 + 8*8*8 + 4*4*4 + i++] =
                            Mipper.mip(
                                    data[I(x, y, z)],       data[I(x+1, y, z)],       data[I(x, y, z+1)],      data[I(x+1, y, z+1)],
                                    data[I(x, y+1, z)],   data[I(x+1, y+1, z)],  data[I(x, y+1, z+1)], data[I(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }

        //Mip L4
        data[16*16*16 + 8*8*8 + 4*4*4 + 2*2*2] =
                Mipper.mip(
                        data[J(0, 0, 0)], data[J(1, 0, 0)], data[J(0, 0, 1)], data[J(1, 0, 1)],
                        data[J(0, 1, 0)], data[J(1, 1, 0)], data[J(0, 1, 1)], data[J(1, 1, 1)],
                        mapper);
    }
}
