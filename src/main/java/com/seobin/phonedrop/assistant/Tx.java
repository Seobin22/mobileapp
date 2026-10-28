package com.seobin.phonedrop.assistant;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 모든 상태 변경(작업 실행, 사용자 액션, 관리 API)을 하나의 락 + 트랜잭션으로 직렬화한다.
 * 단일 사용자·저빈도라 이 편이 DB 행 잠금보다 단순하고 확실하다.
 * 같은 스레드에서 다시 들어오면 바깥 트랜잭션에 합류한다.
 */
@Component
public class Tx {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final TransactionTemplate template;

    public Tx(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    public <T> T run(Supplier<T> work) {
        lock.lock();
        try {
            return template.execute(status -> work.get());
        } finally {
            lock.unlock();
        }
    }

    public void exec(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }
}
