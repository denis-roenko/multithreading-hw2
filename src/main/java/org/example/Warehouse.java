package org.example;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Log4j2
@Getter
public class Warehouse extends Thread {

    private final Lock truckLock, loadUnloadLock;
    private final Condition arrivingTruck, loadUnloadTruck;
    private final List<Block> storage = new ArrayList<>();
    private final Queue<Truck> arrivedTrucks = new LinkedList<>(); // Очередь прибывших грузовиков

    public Warehouse(String name) {
        super(name);
        truckLock = new ReentrantLock();
        loadUnloadLock = new ReentrantLock();
        arrivingTruck = truckLock.newCondition();
        loadUnloadTruck = loadUnloadLock.newCondition();
    }

    public Warehouse(String name, Collection<Block> initialStorage) {
        this(name);
        storage.addAll(initialStorage);
    }

    @Override
    public void run() {
        Truck truck;
        while (!currentThread().isInterrupted()) {
            truck = getNextArrivedTruck();
            if (truck == null) {
                try {
                    sleep(100);
                } catch (InterruptedException e) {
                    if (currentThread().isInterrupted()) {
                        break;
                    }
                }
                continue;
            }
            if (truck.getBlocks().isEmpty()) {
                loadTruck(truck);
            } else {
                unloadTruck(truck);
            }
        }
        log.info("Warehouse thread interrupted");

    }

    private void loadTruck(Truck truck) {
        log.info("Loading truck {}", truck.getName());
        Collection<Block> blocksToLoad = getFreeBlocks(truck.getCapacity());
        try {
            sleep(10L * blocksToLoad.size());
        } catch (InterruptedException e) {
            log.error("Interrupted while loading truck", e);
        }

        loadUnloadLock.lock();
        try {
            truck.getBlocks().addAll(blocksToLoad);
            loadUnloadTruck.signal(); // Сигнализируем методу arrive() о полной загрузке грузовика
        } finally {
            loadUnloadLock.unlock();
        }

        log.info("Truck loaded {}", truck.getName());
    }

    private Collection<Block> getFreeBlocks(int maxItems) {
        // Захватываем блокировку для безопасного извлечения блоков из хранилища
        loadUnloadLock.lock();
        try {
            List<Block> blocks = new ArrayList<>();
            for (int i = 0; i < maxItems; i++) {
                blocks.add(storage.remove(0));
            }
            return blocks;
        }
        finally {
            loadUnloadLock.unlock();
        }
    }

    private void returnBlocksToStorage(List<Block> returnedBlocks) {
        // Захватываем блокировку для безопасного добавления блоков в хранилище
        loadUnloadLock.lock();
        try {
            storage.addAll(returnedBlocks);
            loadUnloadTruck.signal(); // Сигнализируем методу arrive() о полной разгрузке грузовика
        } finally {
            loadUnloadLock.unlock();
        }
    }

    private void unloadTruck(Truck truck) {
        log.info("Unloading truck {}", truck.getName());
        List<Block> arrivedBlocks = truck.getBlocks();
        try {
            sleep(100L * arrivedBlocks.size());
        } catch (InterruptedException e) {
            log.error("Interrupted while unloading truck", e);
        }
        returnBlocksToStorage(arrivedBlocks);
        truck.getBlocks().clear();
        log.info("Truck unloaded {}", truck.getName());
    }

    private Truck getNextArrivedTruck() {
        truckLock.lock();
        try {
            // Ожидаем прибытия грузовика
            while (arrivedTrucks.isEmpty()) {
                arrivingTruck.await();
            }
        } catch (InterruptedException e) {
            log.info("Warehouse thread interrupted");
        } finally {
            truckLock.unlock();
        }
        // Берём грузовик из очереди прибывших
        return arrivedTrucks.poll();
    }


    public void arrive(Truck truck) {
        // По прибытии грузовика, добавляем его в список ожидающих у склада
        // и сигнализируем методу getNextArrivedTruck() о прибытии грузовика
        truckLock.lock();
        try {
            arrivedTrucks.add(truck);
            arrivingTruck.signal();
        } finally {
            truckLock.unlock();
        }

        // Ожидаем загрузки/разгрузки грузовика
        loadUnloadLock.lock();
        try {
            loadUnloadTruck.await();
        }
        catch (InterruptedException e) {
            log.info("Warehouse thread interrupted");
        }
        finally {
            loadUnloadLock.unlock();
        }
    }
}
