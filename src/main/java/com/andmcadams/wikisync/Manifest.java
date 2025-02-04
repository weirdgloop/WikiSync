package com.andmcadams.wikisync;

import lombok.Data;

import java.util.ArrayList;

@Data
public class Manifest
{
    final int version = -1;
    final int[] varbits = new int[0];
    final int[] varps = new int[0];
    final ArrayList<Integer> collections = new ArrayList<>();
}