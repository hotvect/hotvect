//package com.hotvect.core.transform.ranking;
//
//import com.hotvect.api.data.RawValue;
//
//import java.util.Map;
//
//class MemoizedInteractionTransformationTest {
//    private static enum Meh {
//        a, b, c
//    }
//    public void test(){
//        MemoizedInteractionTransformation<Map<String, String>, String, Meh> test = (context, s) -> {
//            Integer i = context.getAttribute(Meh.a);
//            return RawValue.singleString(s + i);
//        };
//    }
//
//}