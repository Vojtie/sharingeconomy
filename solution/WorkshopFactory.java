/*
 * University of Warsaw
 * Concurrent Programming Course 2022/2023
 * Java Assignment
 *
 * Author: Konrad Iwanicki (iwanicki@mimuw.edu.pl)
 */

import java.util.Collection;

public final class WorkshopFactory {

    public final static Workshop newWorkshop(Collection<Workplace> workplaces) {
        return new LitWorkshop(workplaces);
    }
    
}
