package com.jproxy;

import java.util.concurrent.*;

public class ThreadTest {
    public static void main(String[] args) {
        //1、继承Thread类
        class HelloThread extends Thread {
            public void run() {
                System.out.println("1.extends Thread");
            }
        }
        new HelloThread().start();
        //2、实现Runnable接口
        Runnable helloWorld = () -> System.out.println("2.implements Runnable");
        new Thread(helloWorld).start();
        //3、实现Callable接口通过FutureTask包装器来创建Thread线程
        class HelloCallable implements Callable<String> {
            public String call() {
                return "3.implements Callable and use FutureTask";
            }
        }
        FutureTask<String> futureTask = new FutureTask<>(new HelloCallable());
        new Thread(futureTask).start();
        try {
            System.out.println(futureTask.get());
        } catch (Exception e) {
            e.printStackTrace();
        }


        //4、使用线程池工具类Executors、ExecutorService、Callable、Future
        ExecutorService executorService = Executors.newCachedThreadPool();
        //提交任务，如果执行成功future返回null
        executorService.submit(() -> System.out.println("Executors->ExecutorService"));
        //提交任务，如果执行成功future返回结果
        Future<String> future = executorService.submit(new HelloCallable());
        executorService.shutdown();
        try {
            boolean result = executorService.awaitTermination(10, TimeUnit.SECONDS);
            System.out.println("executorService.awaitTermination:" + result);
            if (result) {
                System.out.println("awaitTermination:success");
                //获取结果
                System.out.println(future.get());
            } else {
                System.out.println("awaitTermination:fail");
                //释放资源
                executorService.shutdownNow();
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
