package com.andmcadams.wikisync;

import lombok.Data;

@Data
public class Manifest
{
    final int version;
    final int[] varbits;
    final int[] varps;
}