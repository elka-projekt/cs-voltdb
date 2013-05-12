package org.voltdb;

/**
 * Information of used memory
 *
 * @author Kamil Szmit
 */
public class Memory
{
    private static float limitUsagePercentage;
    private static float percentageOfDataToMove;
    private static boolean coldStorageIsEnabled;
    /**
     * Gets the percentage of used random access memory
     *
     * @return the number of percentages of used RAM
     */
    public static double GetUsage()
    {
        Runtime runtime = Runtime.getRuntime();
        double total = runtime.totalMemory();
        return 100 * (total - runtime.freeMemory()) / total;
    }
    /**
     * Checks if the used memory reached the limit
     *
     * @return true if the memory used is equal to or greater than the limit
     */
    public static boolean ShouldBeMoveOnDisk()
    {
        return (limitUsagePercentage <= GetUsage());
    }
    /**
     * Sets the limit percentage of used RAM
     *
     * @param limit the number of percentages
     */
    public static void SetLimitUsagePercentage(final float limit)
    {
        limitUsagePercentage = limit;
    }
    /**
     * Gets the percentage of data to move on disk when the memory used is equal to or greater than the limit
     *
     * @return the number of percentages of data
     */
    public static float GetPercentageOfDataToMove()
    {
        return percentageOfDataToMove;
    }
    /**
     * Sets the percentage of data to move on disk when the memory used is equal to or greater than the limit
     *
     * @param percentage the number of percentages of data
     */
    public static void SetPercentageOfDataToMove(final float percentage)
    {
        percentageOfDataToMove = percentage;
    }
    /**
     * Checks if cold storage is enabled
     *
     * @return true if cold storage is enabled
     */
    public static boolean ColdStorageIsEnabled()
    {
        return coldStorageIsEnabled;
    }
    /**
     * Sets cold storage enabled or disabled
     *
     * @param isEnabled true if cold storage is enabled
     */
    public static void SetColdStorageEnabled(final boolean isEnabled)
    {
        coldStorageIsEnabled = isEnabled;
    }
}
