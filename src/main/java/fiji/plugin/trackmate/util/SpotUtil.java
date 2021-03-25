package fiji.plugin.trackmate.util;

import java.util.Iterator;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotRoi;
import fiji.plugin.trackmate.detection.DetectionUtils;
import net.imagej.ImgPlus;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.Sampler;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

public class SpotUtil
{

	public static final < T extends RealType< T > > IterableInterval< T > iterable( final Spot spot, final ImgPlus< T > img )
	{
		// Prepare neighborhood
		final IterableInterval< T > neighborhood;
		final SpotRoi roi = spot.getRoi();
		if ( null != roi && DetectionUtils.is2D( img ) )
		{
			// Operate on ROI only if we have one and the image is 2D.
			neighborhood = new SpotRoiIterable<>( spot, img );
		}
		else
		{
			// Otherwise default to circle / sphere.
			neighborhood = new SpotNeighborhood<>( spot, img );
		}

		final int npixels = ( int ) neighborhood.size();
		if ( npixels <= 1 )
		{
			final double[] calibration = TMUtils.getSpatialCalibration( img );
			final long[] min = new long[ img.numDimensions() ];
			final long[] max = new long[ img.numDimensions() ];
			for ( int d = 0; d < min.length; d++ )
			{
				final long center = Math.round( spot.getFeature( Spot.POSITION_FEATURES[ d ] ).doubleValue() / calibration[ d ] );
				min[ d ] = center;
				max[ d ] = center + 1;
			}

			final Interval interval = new FinalInterval( min, max );
			return Views.interval( img, interval );
		}
		else
		{
			return neighborhood;
		}
	}

	private static final class SpotRoiIterable< T > implements IterableInterval< T >
	{

		private final FinalInterval interval;

		private final Spot spot;

		private final ImgPlus< T > img;

		public SpotRoiIterable( final Spot spot, final ImgPlus< T > img )
		{
			this.spot = spot;
			this.img = img;
			final SpotRoi roi = spot.getRoi();
			final double[] x = roi.toPolygonX( img.averageScale( 0 ), 0, spot.getDoublePosition( 0 ), 1. );
			final double[] y = roi.toPolygonX( img.averageScale( 1 ), 0, spot.getDoublePosition( 1 ), 1. );
			final long minX = ( long ) Math.floor( Util.min( x ) );
			final long maxX = ( long ) Math.ceil( Util.max( x ) );
			final long minY = ( long ) Math.floor( Util.min( y ) );
			final long maxY = ( long ) Math.ceil( Util.max( y ) );
			interval = Intervals.createMinMax( minX, minY, maxX, maxY );
		}

		@Override
		public long size()
		{
			int n = 0;
			final Cursor< T > cursor = cursor();
			while ( cursor.hasNext() )
			{
				cursor.fwd();
				n++;
			}
			return n;
		}

		@Override
		public T firstElement()
		{
			return cursor().next();
		}

		@Override
		public Object iterationOrder()
		{
			return this;
		}

		@Override
		public double realMin( final int d )
		{
			return interval.realMin( d );
		}

		@Override
		public double realMax( final int d )
		{
			return interval.realMax( d );
		}

		@Override
		public int numDimensions()
		{
			return 2;
		}

		@Override
		public long min( final int d )
		{
			return interval.min( d );
		}

		@Override
		public long max( final int d )
		{
			return interval.max( d );
		}

		@Override
		public Cursor< T > cursor()
		{
			return new MyCursor< T >( spot, img );
		}

		@Override
		public Cursor< T > localizingCursor()
		{
			return cursor();
		}

		@Override
		public Iterator< T > iterator()
		{
			return cursor();
		}
	}

	private static final class MyCursor< T > implements Cursor< T >
	{

		private final Spot spot;

		private final ImgPlus< T > img;

		private final FinalInterval interval;

		private Cursor< T > cursor;

		private final double[] x;

		private final double[] y;

		private boolean hasNext;

		private RandomAccess< T > ra;

		public MyCursor( final Spot spot, final ImgPlus< T > img )
		{
			this.spot = spot;
			this.img = img;
			final SpotRoi roi = spot.getRoi();
			x = roi.toPolygonX( img.averageScale( 0 ), 0, spot.getDoublePosition( 0 ), 1. );
			y = roi.toPolygonY( img.averageScale( 1 ), 0, spot.getDoublePosition( 1 ), 1. );
			final long minX = ( long ) Math.floor( Util.min( x ) );
			final long maxX = ( long ) Math.ceil( Util.max( x ) );
			final long minY = ( long ) Math.floor( Util.min( y ) );
			final long maxY = ( long ) Math.ceil( Util.max( y ) );
			interval = Intervals.createMinMax( minX, minY, maxX, maxY );
			reset();
		}

		@Override
		public T get()
		{
			return ra.get();
		}

		@Override
		public void fwd()
		{
			ra.setPosition( cursor );
			fetch();
		}

		private void fetch()
		{
			while ( cursor.hasNext() )
			{
				cursor.fwd();
				if ( isInside( cursor, x, y ) )
				{
					hasNext = cursor.hasNext();
					return;
				}
			}
			hasNext = false;
		}

		private static final boolean isInside( final Localizable localizable, final double[] x, final double[] y )
		{
			// Taken from Imglib2-roi GeomMaths. No edge case.
			final double xl = localizable.getDoublePosition( 0 );
			final double yl = localizable.getDoublePosition( 1 );

			int i;
			int j;
			boolean inside = false;
			for ( i = 0, j = x.length - 1; i < x.length; j = i++ )
			{
				final double xj = x[ j ];
				final double yj = y[ j ];

				final double xi = x[ i ];
				final double yi = y[ i ];

				if ( ( yi > yl ) != ( yj > yl ) && ( xl < ( xj - xi ) * ( yl - yi ) / ( yj - yi ) + xi ) )
					inside = !inside;
			}
			return inside;
		}

		@Override
		public void reset()
		{
			final IntervalView< T > view = Views.interval( img, interval );
			cursor = view.localizingCursor();
			ra = view.randomAccess( interval );
			fetch();
		}

		@Override
		public double getDoublePosition( final int d )
		{
			return ra.getDoublePosition( d );
		}

		@Override
		public int numDimensions()
		{
			return 2;
		}

		@Override
		public void jumpFwd( final long steps )
		{
			for ( int i = 0; i < steps; i++ )
				fwd();
		}

		@Override
		public boolean hasNext()
		{
			return hasNext;
		}

		@Override
		public T next()
		{
			fwd();
			return get();
		}

		@Override
		public long getLongPosition( final int d )
		{
			return ra.getLongPosition( d );
		}

		@Override
		public Cursor< T > copyCursor()
		{
			return new MyCursor<>( spot, img );
		}

		@Override
		public Sampler< T > copy()
		{
			return copyCursor();
		}
	}
}